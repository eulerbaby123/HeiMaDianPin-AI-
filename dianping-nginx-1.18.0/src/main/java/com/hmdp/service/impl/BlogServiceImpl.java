
package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户 + 检查点赞状态
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result updateLike(Long id) {
        // 1. 获取当前用户（未登录直接返回失败）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + id;

        // 2. 预热：确保Redis Key存在（避免zScore查询null的逻辑问题）
        if (!stringRedisTemplate.hasKey(key)) {
            preloadBlogLikedDataToRedis(id);
        }

        // 3. 判断当前用户有没有点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.1 未点赞：数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            // 3.2 保存用户到Redis的ZSet（按时间戳排序）
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4. 已点赞：取消点赞，数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            // 4.2 从Redis移除用户
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;

        // 1. 预热：Redis无数据时，从数据库同步点赞数据
        if (!stringRedisTemplate.hasKey(key) || stringRedisTemplate.opsForZSet().zCard(key) == 0) {
            preloadBlogLikedDataToRedis(id);
        }

        // 2. 查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 3. 解析用户ID + 查询用户信息
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户（未登录直接返回失败）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        blog.setUserId(user.getId());

        // 2. 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        if (blog.getShopId() != null) {
            stringRedisTemplate.delete(RedisConstants.AI_SHOP_SUMMARY_KEY + blog.getShopId());
        }

        // 3. 查询作者的所有粉丝（批量查询，避免循环查库）
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if (follows.isEmpty()) {
            return Result.ok(blog.getId());
        }

        // 4. 批量推送笔记ID给所有粉丝（优化：避免循环操作Redis）
        String finalBlogId = blog.getId().toString();
        long now = System.currentTimeMillis();
        for (Follow follow : follows) {
            Long followerId = follow.getUserId();
            String feedKey = FEED_KEY + followerId;
            stringRedisTemplate.opsForZSet().add(feedKey, finalBlogId, now);
        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 修正方法名拼写错误 + 获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key = FEED_KEY + userId;

        // 2. 预热：Redis无feed数据时，从数据库同步用户关注的博主的笔记
        if (!stringRedisTemplate.hasKey(key) || stringRedisTemplate.opsForZSet().zCard(key) == 0) {
            preloadUserFeedDataToRedis(userId);
        }

        // 3. 查询收件箱（ZSet按时间戳倒序分页）
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, SystemConstants.MAX_PAGE_SIZE);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(new ScrollResult());
        }

        // 4. 解析数据：blogId、minTime、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1 获取笔记ID
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2 获取时间戳（分数）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        // 5. 根据ID查询笔记 + 补全用户信息 + 检查点赞状态
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        blogs.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });

        // 6. 封装返回结果
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(os);
        result.setMinTime(minTime);
        return Result.ok(result);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询笔记
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 2. 补全用户信息 + 检查点赞状态
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    // ==================== 私有工具方法（Redis预热 + 数据补全） ====================
    /**
     * 检查当前用户是否点赞了该笔记（兼容未登录场景）
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false); // 未登录默认未点赞
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();

        // 预热：确保Redis有数据
        if (!stringRedisTemplate.hasKey(key)) {
            preloadBlogLikedDataToRedis(blog.getId());
        }

        // 判断是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 补全笔记的用户信息（作者昵称、头像）
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    /**
     * 预热：从数据库同步单篇笔记的点赞数据到Redis
     */
    private void preloadBlogLikedDataToRedis(Long blogId) {
        // 注意：此处简化处理——若需精准同步点赞用户，需设计点赞记录表
        // 基础预热：创建Redis Key，确保后续操作不返回null
        String key = BLOG_LIKED_KEY + blogId;
        if (!stringRedisTemplate.hasKey(key)) {
            stringRedisTemplate.opsForZSet().add(key, "temp", System.currentTimeMillis()); // 临时占位
            stringRedisTemplate.opsForZSet().remove(key, "temp"); // 移除占位，仅保留Key
        }
    }

    /**
     * 预热：从数据库同步用户的Feed数据到Redis
     */
    /**
     * 预热：从数据库同步用户的Feed数据到Redis
     */
    /**
     * 预热：从数据库同步用户的Feed数据到Redis
     */
    private void preloadUserFeedDataToRedis(Long userId) {
        String key = FEED_KEY + userId;
        // 1. 查询用户关注的所有博主ID
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        if (follows.isEmpty()) {
            return;
        }
        List<Long> followUserIds = follows.stream().map(Follow::getFollowUserId).collect(Collectors.toList());

        // 2. 查询这些博主的最新笔记
        List<Blog> blogs = query()
                .in("user_id", followUserIds)
                .orderByDesc("create_time")
                .list();

        // 3. 批量写入Redis Feed（修复LocalDateTime.toInstant()需传参问题）
        ZSetOperations<String, String> zSetOps = stringRedisTemplate.opsForZSet();
        // 定义默认时区（东八区，适配国内业务）
        ZoneId zoneId = ZoneId.systemDefault(); // 或显式指定：ZoneId.of("Asia/Shanghai")
        for (Blog blog : blogs) {
            // 防护：createTime为空时用当前时间兜底
            long timestamp;
            if (blog.getCreateTime() != null) {
                // 核心修复：LocalDateTime转Instant必须传时区参数
                timestamp = blog.getCreateTime().atZone(zoneId).toInstant().toEpochMilli();
            } else {
                timestamp = System.currentTimeMillis();
            }
            // ZSet的score需要double类型
            zSetOps.add(key, blog.getId().toString(), (double) timestamp);
        }
    }
}
