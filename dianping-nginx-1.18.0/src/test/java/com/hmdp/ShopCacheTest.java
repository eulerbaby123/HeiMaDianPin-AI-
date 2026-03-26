package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class ShopCacheTest {

    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Test
    public void testCacheAllShops() {
        // 1. 从数据库中查询所有店铺信息
        List<Shop> shopList = shopService.list();

        // 2. 遍历店铺列表，将每个店铺的信息写入Redis
        for (Shop shop : shopList) {
            // 生成Redis键
            String key = RedisConstants.CACHE_SHOP_KEY + shop.getId();

            // 将店铺信息写入Redis，并设置逻辑过期时间
            cacheClient.setWithLogicalExpire(key, shop, RedisConstants.CACHE_SHOP_TTL, TimeUnit.HOURS);
        }

        System.out.println("All shop information has been cached to Redis.");
    }
}
