package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 缓存穿透
    public <R, ID> R queryWithCacheThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbCallback, Long time, TimeUnit unit) {
        // 1. 先尝试从redis读取缓存
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        // 2. 如果有直接返回
        if (StrUtil.isNotBlank(json)) {
            // 返回之前， 要转化为java对象
            return JSONUtil.toBean(json, type);
        }
        // 解决缓存穿透：如果是空值， 直接返回，空字符串是!=null的， 所以满足条件会进来
        // 因为前面已经判断过了， 能够走下来的要么是空字符串， 要么是null
        if (json != null) {
            return null;
        }
        // 3. 如果没有取数据库读取
        R r = dbCallback.apply(id);
        // 4. 数据库没有返回404
        if (r == null) {
            // 解决缓存穿透：如果是空值， 把空值写入redis缓存， 并且设置过期时间
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("商品不存在");
            return null;
        }
        // 5 数据库有，返回数据， 并且写入redis缓存, 写入的时候， 注意要把java对象转化为json字符串
        this.set(keyPrefix + id, r, time, unit);
        return r;
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);

    // 缓存击穿： 用逻辑过期
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbCallback, Long time, TimeUnit unit) {
        // 1. 先尝试从redis读取缓存
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        // 2. 如果有直接返回
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 3. 命中需要把Json,反序列化为java对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5. 未过期， 直接返回店铺信息
            return r;
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
                    // 先查数据库
                    R r1 = dbCallback.apply(id);
                    this.setWithLogicalExpire(keyPrefix + id, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        // 6.3 失败就返回旧数据
        return r;
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
}
