package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            log.error("用户{}不允许重复下单", userId);
            // 清理Redis重复标记
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
            return;
        }

        try {
            // 核心修改：在使用时动态获取代理（避免启动时获取失败）
            if (proxy == null) {
                proxy = (IVoucherOrderService) AopContext.currentProxy();
            }
            proxy.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理订单{}失败", voucherOrder.getId(), e);
            // 异常时清理Redis标记
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        int r = result != null ? result.intValue() : -1;
        if (r != 0) {
            // 兼容你的Lua脚本返回-1的情况（库存获取失败）
            String msg = r == -1 ? "系统异常" : (r == 1 ? "库存不足" : "不能重复下单");
            return Result.fail(msg);
        }

        // 构建订单
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);

        // 发送MQ消息
        String jsonStr = JSONUtil.toJsonStr(order);
        try {
            rabbitTemplate.convertAndSend("X", "XA", jsonStr);
        } catch (Exception e) {
            // 发送失败时清理Redis标记
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
            log.error("发送MQ消息失败，订单ID: {}", orderId, e);
            return Result.fail("抢购失败，请重试");
        }

        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 1. 校验一人一单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户{}已购买优惠券{}", userId, voucherId);
            // 清理标记
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
            return;
        }

        // 2. 扣减库存（仅一次）
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!success) {
            log.error("优惠券{}库存不足", voucherId);
            // 清理标记
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
            return;
        }

        // 3. 保存订单到数据库
        save(voucherOrder);
        log.info("订单{}已写入数据库", voucherOrder.getId());
    }
}