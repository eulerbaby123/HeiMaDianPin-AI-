package com.hmdp.tools;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DemoDataSeedService {

    private static final String SHOP_PREFIX = "AI样本店-";
    private static final String BLOG_PREFIX = "AI探店-";
    private static final String SHOP_TYPE_ZSET_KEY = "shop:type:list";
    private static final List<String> AREA_POOL = Arrays.asList("大关", "拱宸桥", "上塘", "远洋乐堤港", "运河上街", "北部新城", "水晶城");
    private static final List<String> ROAD_POOL = Arrays.asList("金华路", "上塘路", "台州路", "丽水路", "湖州街", "杭行路", "拱苑路");
    private static final List<String> OPEN_HOURS_POOL = Arrays.asList("09:00-22:00", "10:00-23:00", "11:00-02:00", "00:00-24:00");
    private static final List<String> DESC_COMMON = Arrays.asList(
            "支持线上核销和到店消费", "门店提供空调与休息区", "高峰期建议提前预约", "可提供停车指引", "环境干净整洁，适合朋友同行");
    private static final List<String> BLOG_FEELINGS = Arrays.asList(
            "整体体验比预期稳定", "服务节奏比较顺畅", "环境对聊天和休息很友好", "出品速度在高峰期也还可以", "二次复购意愿较高");

    @Value("${hmdp.seed.shop-per-type:18}")
    private int shopsPerType;

    @Value("${hmdp.seed.blogs-per-shop:12}")
    private int blogsPerShop;

    @Value("${hmdp.seed.min-users:220}")
    private int minUsers;

    @Value("${hmdp.seed.center-x:120.149993}")
    private double centerX;

    @Value("${hmdp.seed.center-y:30.334229}")
    private double centerY;

    @Value("${hmdp.seed.radius-meters:4600}")
    private int radiusMeters;

    @Resource
    private IShopTypeService shopTypeService;
    @Resource
    private IShopService shopService;
    @Resource
    private IUserService userService;
    @Resource
    private IBlogService blogService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private JdbcTemplate jdbcTemplate;

    public SeedSummary seed() {
        validateArgs();
        ensureShopDescColumn();
        List<ShopType> types = shopTypeService.query().orderByAsc("sort").list();
        if (types == null || types.isEmpty()) {
            throw new IllegalStateException("tb_shop_type为空，无法生成样本数据");
        }

        int newUsers = ensureAuthorUsers();
        Map<Long, String> imageByType = buildTypeBaseImageMap();

        int newShops = 0;
        for (ShopType type : types) {
            newShops += seedShopsForType(type, imageByType.get(type.getId()));
        }

        List<Shop> seededShops = listSeededShops();
        List<Long> authorIds = listAuthorIds();
        int newBlogs = seedBlogsForShops(seededShops, authorIds);

        preloadShopTypeZSet(types);
        preloadShopGeo(types);
        preloadShopCache(seededShops);
        clearAssistantCache();

        return new SeedSummary(types.size(), newUsers, newShops, newBlogs, seededShops.size(), authorIds.size());
    }

    public VerifySummary verify() {
        List<ShopType> types = shopTypeService.query().orderByAsc("sort").list();
        if (types == null) {
            types = Collections.emptyList();
        }

        long seededShopCount = shopService.query().likeRight("name", SHOP_PREFIX).count();
        long seededShopDescCount = shopService.query()
                .likeRight("name", SHOP_PREFIX)
                .isNotNull("shop_desc")
                .ne("shop_desc", "")
                .count();
        long seededBlogCount = blogService.query().likeRight("title", BLOG_PREFIX).count();

        Long shopTypeZsetSize = stringRedisTemplate.opsForZSet().zCard(SHOP_TYPE_ZSET_KEY);
        int geoReadyTypeCount = 0;
        List<String> perTypeGeoStats = new ArrayList<>(types.size());
        for (ShopType type : types) {
            if (type.getId() == null) {
                continue;
            }
            long dbTypeShopCount = shopService.query()
                    .eq("type_id", type.getId())
                    .likeRight("name", shopPrefixByType(type.getId()))
                    .count();
            Long geoSize = stringRedisTemplate.opsForZSet().zCard(RedisConstants.SHOP_GEO_KEY + type.getId());
            long geoCount = geoSize == null ? 0L : geoSize;
            if (geoCount > 0) {
                geoReadyTypeCount++;
            }
            perTypeGeoStats.add(type.getId() + ":" + dbTypeShopCount + "/" + geoCount);
        }

        long cacheReadyShopCount = countSeededShopCacheReady();
        BlogCoverage blogCoverage = loadBlogCoverage();

        return new VerifySummary(
                types.size(),
                seededShopCount,
                seededShopDescCount,
                seededBlogCount,
                blogCoverage.getShopCount(),
                blogCoverage.getMinBlogsPerShop(),
                blogCoverage.getAvgBlogsPerShop(),
                blogCoverage.getMaxBlogsPerShop(),
                shopTypeZsetSize == null ? 0L : shopTypeZsetSize,
                geoReadyTypeCount,
                cacheReadyShopCount,
                String.join(",", perTypeGeoStats)
        );
    }

    private void validateArgs() {
        if (shopsPerType < 1) {
            shopsPerType = 1;
        }
        if (blogsPerShop < 1) {
            blogsPerShop = 1;
        }
        if (minUsers < 30) {
            minUsers = 30;
        }
        if (radiusMeters < 500) {
            radiusMeters = 500;
        }
        if (radiusMeters > 4900) {
            radiusMeters = 4900;
        }
    }

    private void ensureShopDescColumn() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_shop' AND COLUMN_NAME = 'shop_desc'",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        log.warn("tb_shop.shop_desc 不存在，自动执行字段升级");
        jdbcTemplate.execute("ALTER TABLE tb_shop ADD COLUMN shop_desc VARCHAR(1024) NULL COMMENT '商铺简介，描述经营商品、服务与提示信息' AFTER open_hours");
    }

    private int ensureAuthorUsers() {
        List<User> users = userService.query().select("id", "phone", "nick_name").list();
        int existing = users == null ? 0 : users.size();
        if (existing >= minUsers) {
            return 0;
        }

        int need = minUsers - existing;
        Long maxId = Optional.ofNullable(userService.query().select("id").orderByDesc("id").last("limit 1").one())
                .map(User::getId).orElse(1000L);

        List<User> addUsers = new ArrayList<>(need);
        for (int i = 1; i <= need; i++) {
            long n = maxId + i;
            User user = new User();
            user.setPhone(String.format(Locale.ROOT, "199%08d", n));
            user.setPassword("");
            user.setNickName(String.format(Locale.ROOT, "ai_seed_user_%04d", n % 10000));
            user.setIcon("");
            user.setCreateTime(LocalDateTime.now().minusDays(ThreadLocalRandom.current().nextInt(1, 60)));
            user.setUpdateTime(LocalDateTime.now());
            addUsers.add(user);
        }
        userService.saveBatch(addUsers, 200);
        return addUsers.size();
    }

    private Map<Long, String> buildTypeBaseImageMap() {
        List<Shop> allShops = shopService.query().select("type_id", "images").isNotNull("images").list();
        Map<Long, String> map = new HashMap<>();
        if (allShops != null) {
            for (Shop shop : allShops) {
                if (shop.getTypeId() == null || StrUtil.isBlank(shop.getImages())) {
                    continue;
                }
                String first = shop.getImages().split(",")[0];
                if (StrUtil.isNotBlank(first) && !map.containsKey(shop.getTypeId())) {
                    map.put(shop.getTypeId(), first);
                }
            }
        }
        String fallback = "https://img.meituan.net/msmerchant/054b5de0ba0b50c18a620cc37482129a45739.jpg";
        return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> StrUtil.blankToDefault(e.getValue(), fallback)));
    }

    private int seedShopsForType(ShopType type, String baseImage) {
        String prefix = shopPrefixByType(type.getId());
        long exists = shopService.query()
                .eq("type_id", type.getId())
                .likeRight("name", prefix)
                .count();
        int need = shopsPerType - (int) exists;
        if (need <= 0) {
            return 0;
        }

        List<Shop> toSave = new ArrayList<>(need);
        for (int i = 1; i <= need; i++) {
            int seq = (int) exists + i;
            toSave.add(buildShop(type, seq, baseImage));
        }
        shopService.saveBatch(toSave, 200);
        return toSave.size();
    }

    private Shop buildShop(ShopType type, int seq, String baseImage) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double[] xy = randomCoordinateInRadius(centerX, centerY, radiusMeters);
        String area = AREA_POOL.get(r.nextInt(AREA_POOL.size()));
        String road = ROAD_POOL.get(r.nextInt(ROAD_POOL.size()));

        Shop shop = new Shop();
        shop.setName(String.format(Locale.ROOT, "%s%03d", shopPrefixByType(type.getId()), seq));
        shop.setTypeId(type.getId());
        shop.setImages(buildShopImages(baseImage));
        shop.setArea(area);
        shop.setAddress(area + road + (r.nextInt(1, 280)) + "号");
        shop.setX(xy[0]);
        shop.setY(xy[1]);
        shop.setAvgPrice((long) randomAvgPrice(type.getName()));
        shop.setSold(r.nextInt(300, 12000));
        shop.setComments(r.nextInt(80, 4500));
        shop.setScore(r.nextInt(37, 50));
        shop.setOpenHours(OPEN_HOURS_POOL.get(r.nextInt(OPEN_HOURS_POOL.size())));
        shop.setShopDesc(buildShopDesc(type.getName(), area));
        shop.setCreateTime(LocalDateTime.now().minusDays(r.nextInt(30, 400)));
        shop.setUpdateTime(LocalDateTime.now().minusDays(r.nextInt(0, 12)));
        return shop;
    }

    private int seedBlogsForShops(List<Shop> shops, List<Long> userIds) {
        if (shops == null || shops.isEmpty() || userIds == null || userIds.isEmpty()) {
            return 0;
        }
        int newBlogs = 0;
        List<Blog> batch = new ArrayList<>();
        for (Shop shop : shops) {
            long exists = blogService.query()
                    .eq("shop_id", shop.getId())
                    .likeRight("title", BLOG_PREFIX + shop.getId() + "-")
                    .count();
            int need = blogsPerShop - (int) exists;
            if (need <= 0) {
                continue;
            }

            for (int i = 1; i <= need; i++) {
                int seq = (int) exists + i;
                batch.add(buildBlog(shop, seq, userIds));
                if (batch.size() >= 400) {
                    blogService.saveBatch(batch, 400);
                    newBlogs += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            blogService.saveBatch(batch, 400);
            newBlogs += batch.size();
        }
        return newBlogs;
    }

    private Blog buildBlog(Shop shop, int seq, List<Long> userIds) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String typeTag = resolveTypeTag(shop.getTypeId());
        String feeling = BLOG_FEELINGS.get(r.nextInt(BLOG_FEELINGS.size()));
        String desc = StrUtil.blankToDefault(shop.getShopDesc(), "门店信息完整，体验稳定。");
        String[] imgs = shop.getImages().split(",");
        String image = imgs.length == 0 ? "" : imgs[0];

        Blog blog = new Blog();
        blog.setShopId(shop.getId());
        blog.setUserId(userIds.get(r.nextInt(userIds.size())));
        blog.setTitle(String.format(Locale.ROOT, "%s%d-%02d %s", BLOG_PREFIX, shop.getId(), seq, typeTag));
        blog.setImages(image);
        blog.setContent("打卡 " + shop.getName() + "，" + feeling + "。"
                + "门店主打：" + desc + "。"
                + "本次重点关注了环境、服务、出品稳定性，整体适合" + typeTag + "场景。");
        blog.setLiked(r.nextInt(0, 700));
        blog.setComments(r.nextInt(0, 120));
        blog.setCreateTime(LocalDateTime.now().minusDays(r.nextInt(1, 220)).minusHours(r.nextInt(0, 23)));
        blog.setUpdateTime(blog.getCreateTime().plusHours(r.nextInt(1, 72)));
        return blog;
    }

    private void preloadShopTypeZSet(List<ShopType> types) {
        stringRedisTemplate.delete(SHOP_TYPE_ZSET_KEY);
        for (ShopType type : types) {
            if (type.getId() == null) {
                continue;
            }
            int sort = type.getSort() == null ? 99 : type.getSort();
            stringRedisTemplate.opsForZSet().add(SHOP_TYPE_ZSET_KEY, type.getId().toString(), sort);
        }
        stringRedisTemplate.expire(SHOP_TYPE_ZSET_KEY, 1, TimeUnit.HOURS);
    }

    private void preloadShopGeo(List<ShopType> types) {
        for (ShopType type : types) {
            if (type.getId() == null) {
                continue;
            }
            String geoKey = RedisConstants.SHOP_GEO_KEY + type.getId();
            List<Shop> shops = shopService.query()
                    .eq("type_id", type.getId())
                    .isNotNull("x")
                    .isNotNull("y")
                    .list();
            if (shops == null || shops.isEmpty()) {
                continue;
            }
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.delete(geoKey);
            stringRedisTemplate.opsForGeo().add(geoKey, locations);
        }
    }

    private void preloadShopCache(List<Shop> seededShops) {
        if (seededShops == null || seededShops.isEmpty()) {
            return;
        }
        for (Shop shop : seededShops) {
            cacheClient.setWithLogicalExpire(
                    RedisConstants.CACHE_SHOP_KEY + shop.getId(),
                    shop,
                    RedisConstants.CACHE_SHOP_TTL,
                    TimeUnit.MINUTES
            );
        }
    }

    private void clearAssistantCache() {
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.AI_ASSISTANT_CACHE_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private List<Shop> listSeededShops() {
        return shopService.query().likeRight("name", SHOP_PREFIX).list();
    }

    private List<Long> listAuthorIds() {
        return userService.query().select("id").list().stream()
                .map(User::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private long countSeededShopCacheReady() {
        List<Shop> seededShops = listSeededShops();
        if (seededShops == null || seededShops.isEmpty()) {
            return 0;
        }
        long hit = 0;
        for (Shop shop : seededShops) {
            if (shop.getId() == null) {
                continue;
            }
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.CACHE_SHOP_KEY + shop.getId()))) {
                hit++;
            }
        }
        return hit;
    }

    private BlogCoverage loadBlogCoverage() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS cnt " +
                        "FROM tb_blog b " +
                        "JOIN tb_shop s ON s.id = b.shop_id " +
                        "WHERE s.name LIKE ? AND b.title LIKE ? " +
                        "GROUP BY b.shop_id",
                SHOP_PREFIX + "%",
                BLOG_PREFIX + "%"
        );
        if (rows == null || rows.isEmpty()) {
            return new BlogCoverage(0, 0, 0D, 0);
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long total = 0L;
        for (Map<String, Object> row : rows) {
            Number n = (Number) row.get("cnt");
            int c = n == null ? 0 : n.intValue();
            min = Math.min(min, c);
            max = Math.max(max, c);
            total += c;
        }
        double avg = rows.isEmpty() ? 0D : (double) total / rows.size();
        if (min == Integer.MAX_VALUE) {
            min = 0;
        }
        if (max == Integer.MIN_VALUE) {
            max = 0;
        }
        return new BlogCoverage(rows.size(), min, avg, max);
    }

    private String shopPrefixByType(Long typeId) {
        return SHOP_PREFIX + typeId + "-";
    }

    private String buildShopImages(String baseImage) {
        String safe = StrUtil.blankToDefault(baseImage, "https://img.meituan.net/msmerchant/054b5de0ba0b50c18a620cc37482129a45739.jpg");
        return safe + "," + safe + "?v=2," + safe + "?v=3";
    }

    private String buildShopDesc(String typeName, String area) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> parts = new ArrayList<>();
        parts.add(area + "门店，主营" + typeName + "相关服务");
        parts.add(DESC_COMMON.get(r.nextInt(DESC_COMMON.size())));
        if (StrUtil.contains(typeName, "美食")) {
            parts.add("支持清淡口味和酸口菜品选择，适合感冒期和轻食需求");
            parts.add("可提供热水、茶饮和短时休息座位");
        } else if (StrUtil.contains(typeName, "KTV") || StrUtil.contains(typeName, "酒吧") || StrUtil.contains(typeName, "轰趴")) {
            parts.add("包厢场景较全，适合聚会和夜间活动");
            parts.add("可选无酒精饮品与零食补给");
        } else if (StrUtil.contains(typeName, "健身")) {
            parts.add("提供基础训练指导，支持新手体验");
            parts.add("配备淋浴、空调和储物空间");
        } else if (StrUtil.contains(typeName, "按摩") || StrUtil.contains(typeName, "SPA")) {
            parts.add("主打放松理疗，环境安静");
            parts.add("门店前台可提供停车路线说明");
        } else if (StrUtil.contains(typeName, "亲子")) {
            parts.add("适合亲子短时活动，安全区域划分明确");
            parts.add("工作日客流相对平稳，体验更从容");
        } else {
            parts.add("支持到店沟通需求，服务项目信息透明");
            parts.add("周边补给便利，可快速购买饮用水");
        }
        return String.join("；", parts);
    }

    private int randomAvgPrice(String typeName) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (StrUtil.contains(typeName, "美食")) return r.nextInt(25, 180);
        if (StrUtil.contains(typeName, "KTV")) return r.nextInt(50, 220);
        if (StrUtil.contains(typeName, "丽人") || StrUtil.contains(typeName, "美睫") || StrUtil.contains(typeName, "美甲")) return r.nextInt(60, 320);
        if (StrUtil.contains(typeName, "健身")) return r.nextInt(40, 260);
        if (StrUtil.contains(typeName, "按摩") || StrUtil.contains(typeName, "SPA")) return r.nextInt(80, 380);
        if (StrUtil.contains(typeName, "酒吧")) return r.nextInt(90, 360);
        if (StrUtil.contains(typeName, "轰趴")) return r.nextInt(120, 520);
        return r.nextInt(40, 260);
    }

    private String resolveTypeTag(Long typeId) {
        ShopType type = shopTypeService.getById(typeId);
        if (type == null || StrUtil.isBlank(type.getName())) {
            return "休闲体验";
        }
        return type.getName();
    }

    private double[] randomCoordinateInRadius(double originLon, double originLat, double maxMeters) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double theta = r.nextDouble(0, Math.PI * 2);
        double radius = Math.sqrt(r.nextDouble()) * maxMeters;
        double dx = radius * Math.cos(theta);
        double dy = radius * Math.sin(theta);
        double lat = originLat + dy / 111_000D;
        double lon = originLon + dx / (111_000D * Math.cos(Math.toRadians(originLat)));
        return new double[]{lon, lat};
    }

    @Data
    @AllArgsConstructor
    public static class SeedSummary {
        private int typeCount;
        private int newUsers;
        private int newShops;
        private int newBlogs;
        private int totalSeededShops;
        private int authorUserCount;
    }

    @Data
    @AllArgsConstructor
    public static class VerifySummary {
        private int typeCount;
        private long seededShopCount;
        private long seededShopDescCount;
        private long seededBlogCount;
        private int shopWithBlogs;
        private int minBlogsPerShop;
        private double avgBlogsPerShop;
        private int maxBlogsPerShop;
        private long shopTypeZsetSize;
        private int geoReadyTypeCount;
        private long cacheReadyShopCount;
        private String perTypeGeoStats;
    }

    @Data
    @AllArgsConstructor
    private static class BlogCoverage {
        private int shopCount;
        private int minBlogsPerShop;
        private double avgBlogsPerShop;
        private int maxBlogsPerShop;
    }
}
