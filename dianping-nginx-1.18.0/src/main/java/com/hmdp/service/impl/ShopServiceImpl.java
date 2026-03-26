package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    // 线程池（保留，用于逻辑过期缓存重建）
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // ========== 核心预热：Redis为空则从数据库同步 ==========
        if (StrUtil.isBlank(json)) {
            Shop dbShop = getById(id);
            if (dbShop == null) {
                return Result.fail("店铺不存在！");
            }
            // 写入Redis（带逻辑过期）
            RedisData redisData = new RedisData();
            redisData.setData(dbShop);
            redisData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL));
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
            return Result.ok(dbShop);
        }

        // 缓存有数据，走逻辑过期策略
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x != null && y != null) {
            int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
            int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
            String key = SHOP_GEO_KEY + typeId;

            preloadShopGeoToRedis(typeId, key);

            // 核心修复：按距离升序查询，并限制每页数量
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                    .search(
                            key,
                            GeoReference.fromCoordinate(x, y),
                            new Distance(5000), // 5公里范围
                            RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                    .includeDistance() // 包含距离
                                    .sortAscending() // 按距离升序（近→远）
                                    .limit(from + pageSize) // 查询到当前页末尾
                    );

            if (results == null || results.getContent().isEmpty()) {
                return Result.ok(Collections.emptyList());
            }

            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> allList = results.getContent();
            // 截取当前页数据
            if (from >= allList.size()) {
                return Result.ok(Collections.emptyList());
            }
            int toIndex = Math.min(from + pageSize, allList.size());
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> currentPageList = allList.subList(from, toIndex);

            // 解析ID和距离，并确保按距离排序
            List<Long> ids = new ArrayList<>();
            Map<String, Distance> distanceMap = new HashMap<>();
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : currentPageList) {
                String shopIdStr = result.getContent().getName();
                ids.add(Long.valueOf(shopIdStr));
                distanceMap.put(shopIdStr, result.getDistance());
            }

            if (ids.isEmpty()) {
                return Result.ok(Collections.emptyList());
            }

            // 查询店铺，并按Redis返回的顺序（距离升序）排列
            String idStr = StrUtil.join(",", ids);
            List<Shop> shops = query().in("id", ids)
                    .last("order by field(id," + idStr + ")") // 按距离排序后的ID顺序排列
                    .list();

            // 给店铺设置距离（单位：米）
            // 替换原来的距离设置代码
            for (Shop shop : shops) {
                // 核心修复：用getValue()获取距离值（单位：米）
                Distance distance = distanceMap.get(shop.getId().toString());
                // 确保distance不为空，避免空指针
                if (distance != null) {
                    // getValue() 返回Double类型，单位默认是米（Redis Geo默认单位）
                    shop.setDistance(distance.getValue());
                }
            }

            return Result.ok(shops);
        }

        // 无坐标，走数据库分页
        Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    // ========== 预热工具方法：仅Redis无数据时执行 ==========
    private boolean hasGeoDataInRedis(String key) {
        Long count = stringRedisTemplate.opsForZSet().zCard(key);
        return count != null && count > 0;
    }

    private void preloadShopGeoToRedis(Integer typeId, String key) {
        // 已有数据，直接返回
        if (hasGeoDataInRedis(key)) {
            return;
        }
        // 从数据库查询并写入Redis Geo
        List<Shop> shops = query().eq("type_id", typeId).isNotNull("x").isNotNull("y").list();
        if (shops.isEmpty()) {
            return;
        }
        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
        for (Shop shop : shops) {
            locations.add(new RedisGeoCommands.GeoLocation<>(
                    shop.getId().toString(), new Point(shop.getX(), shop.getY())
            ));
        }
        stringRedisTemplate.opsForGeo().add(key, locations);
    }

    // 缓存重建方法（保留）
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
