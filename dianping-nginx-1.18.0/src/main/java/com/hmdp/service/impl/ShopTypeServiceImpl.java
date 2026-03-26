package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopTypeService typeService;
    @Override
    public Result querySort() {

        String key = "shop:type:list";
        Set<String> typeSet = stringRedisTemplate.opsForZSet().range(key, 0, Long.MAX_VALUE);
        //2。没有则查询数据库
        if (CollectionUtils.isEmpty(typeSet)) {
            List<ShopType> typeList1 = typeService
                    .query().orderByAsc("sort").list();
            //3.数据库没有则返回错误信息
            if (typeList1 == null)
                return Result.fail("没有shoptype数据");
            //5.数据库有则缓存到redis中
            for (ShopType type : typeList1) {
                stringRedisTemplate.opsForZSet().add(key, type.getId().toString(), type.getSort());
                stringRedisTemplate.expire(key, 1, TimeUnit.HOURS);
            }
            return Result.ok(typeList1);
        }
        List<ShopType> typeList2 = typeSet.stream()
                .map(id -> typeService.getById(Long.valueOf(id)))
                .collect(Collectors.toList());
        return Result.ok(typeList2);

    }
}
