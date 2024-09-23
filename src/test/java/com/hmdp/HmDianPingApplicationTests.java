package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveShop() {
        shopService.cacheShopToRedis(1L, 10L);
    }

    @Test
    void loadShopData() {
        // 先查询所有店铺
        List<Shop> list = shopService.list();
        // 根据typeID分组，一个id存一个集合, 这里比较优雅的写法就是Collectors.groupingBy，这样就自动根据typeId分组了
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 分批写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            // 获取该类型的下的所有店铺
            List<Shop> shops = entry.getValue();
            List<org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());

            // 将店铺写入redis GEOADD key 经度 纬度 member
            for (Shop shop : shops) {
                // 存入redis，但是这个方法不优雅，每次循环需要写一次redis
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            // 优雅的写法，一次性写入，我们把shop全部转为locations，然后一次性写入
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user" + i;
            if (j == 999) {
                // 一次性添加1000个元素
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count" + count);
    }

}
