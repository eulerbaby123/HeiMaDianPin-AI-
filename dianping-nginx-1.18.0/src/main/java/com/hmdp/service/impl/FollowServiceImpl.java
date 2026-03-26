
package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return Result.fail("请先登录");
        }
        Long userId = userDTO.getId();
        String key = "follows:" + userId;

        if(isFollow) {
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return Result.ok(false);
        }
        Long userId = userDTO.getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return Result.fail("请先登录");
        }
        Long userId = userDTO.getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;

        // ========== 核心预热：Redis为空则从数据库同步（仅执行一次） ==========
        preloadFollowToRedis(userId, key1);
        preloadFollowToRedis(id, key2);

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    // ========== 预热工具方法：仅Redis无数据时执行 ==========
    private void preloadFollowToRedis(Long userId, String key) {
        // 1. Redis已有数据，直接返回（避免重复预热）
        if (stringRedisTemplate.hasKey(key) && stringRedisTemplate.opsForSet().size(key) > 0) {
            return;
        }
        // 2. Redis无数据，从数据库查询并写入
        List<Follow> follows = query().eq("user_id", userId).list();
        if (follows.isEmpty()) {
            return;
        }
        // 批量写入Redis Set
        String[] followIds = follows.stream()
                .map(f -> f.getFollowUserId().toString())
                .toArray(String[]::new);
        stringRedisTemplate.opsForSet().add(key, followIds);
    }
}
