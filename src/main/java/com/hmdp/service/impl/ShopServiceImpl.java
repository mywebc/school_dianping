package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透的代码
//        Shop shop = queryWithCacheThrough(id);

        // 缓存击穿： 互斥锁
//        Shop shop = queryWithMutex(id);

        // 缓存击穿： 逻辑过期
        Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    // 利用redis里的setnx 来当作互斥锁，因为setnx只有不存在的时候才会设置成功
    // 这里写获取锁和释放锁的两个方法
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 这里不要直接返回flag， flag是引用类型， 直接返回拆箱的时候有可能会发生空指针异常
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁， 直接就删除就好
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);

    // 缓存击穿： 用逻辑过期
    public Shop queryWithLogicalExpire(Long id) {
        // 1. 先尝试从redis读取缓存
        String shopCache = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 如果有直接返回
        if (StrUtil.isBlank(shopCache)) {
            return null;
        }
        // 3. 命中需要把Json,反序列化为java对象
        RedisData redisData = JSONUtil.toBean(shopCache, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5. 未过期， 直接返回店铺信息
            return shop;
        }
        // 6. 过期了就缓存重建
        // 6.1 获取互斥锁
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        // 6.2 成功就开启独立线程进行缓存重建 (推荐使用线程池的方式)
        if (isLock) {
            // 提交一个任务到线程池
            CACHE_REBUILD_POOL.submit(() -> {
                try {
                    // 重建缓存
                    this.cacheShopToRedis(id, RedisConstants.CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        // 6.3 失败就返回旧数据
        return shop;
    }

    // 缓存击穿： 用互斥锁
    public Shop queryWithMutex(Long id) {
        // 1. 先尝试从redis读取缓存
        String shopCache = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 如果有直接返回
        if (StrUtil.isNotBlank(shopCache)) {
            // 返回之前， 要转化为java对象
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
//            return Result.ok(shop);
            return shop;
        }
        // 解决缓存穿透：如果是空值， 直接返回，空字符串是!=null的， 所以满足条件会进来
        // 因为前面已经判断过了， 能够走下来的要么是空字符串， 要么是null
        if (shopCache != null) {
//            return Result.fail("商品不存在");
            return null;
        }
        Shop shop = null;
        try {
            // 3. 缓存击穿： 实现缓存重建
            // 3.1 获取互斥锁
            boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            // 3.2 失败就休眠重试
            if (!isLock) {
                Thread.sleep(50);
                // 递归， 再次尝试
                return queryWithMutex(id);
            }
            // 3.3 成功就查询数据库，并写入
            shop = getById(id);
            // 4. 数据库没有返回404
            if (shop == null) {
                // 解决缓存穿透：如果是空值， 把空值写入redis缓存， 并且设置过期时间
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //            return Result.fail("商品不存在");
                return null;
            }
            // 5 数据库有，返回数据， 并且写入redis缓存, 写入的时候， 注意要把java对象转化为json字符串
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 缓存击穿： 释放互斥锁
            unLock(RedisConstants.LOCK_SHOP_KEY + id);
        }
//        return Result.ok(shop);
        return shop;
    }

    // 封装缓存穿透的代码， 防止丢失
    public Shop queryWithCacheThrough(Long id) {
        // 1. 先尝试从redis读取缓存
        String shopCache = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 如果有直接返回
        if (StrUtil.isNotBlank(shopCache)) {
            // 返回之前， 要转化为java对象
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
//            return Result.ok(shop);
            return shop;
        }
        // 解决缓存穿透：如果是空值， 直接返回，空字符串是!=null的， 所以满足条件会进来
        // 因为前面已经判断过了， 能够走下来的要么是空字符串， 要么是null
        if (shopCache != null) {
//            return Result.fail("商品不存在");
            return null;
        }
        // 3. 如果没有取数据库读取
        Shop shop = getById(id);
        // 4. 数据库没有返回404
        if (shop == null) {
            // 解决缓存穿透：如果是空值， 把空值写入redis缓存， 并且设置过期时间
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("商品不存在");
            return null;
        }
        // 5 数据库有，返回数据， 并且写入redis缓存, 写入的时候， 注意要把java对象转化为json字符串
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);
        return shop;
    }

    // 写一个方法，单元测试的时候调用，方法的作用就是： 把mysql的店铺数据查出来， 加上逻辑的过期时间， 再写入redis
    public void cacheShopToRedis(Long id, Long expireSeconds) {
        // 1. 先查询数据库
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 2. 写入redis, 这里注意就不设置过期时间
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional // 保持事务一致性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 1. 先更新数据库
        updateById(shop);
        // 2. 再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        // 3. 返回
        return Result.ok();
    }
}
