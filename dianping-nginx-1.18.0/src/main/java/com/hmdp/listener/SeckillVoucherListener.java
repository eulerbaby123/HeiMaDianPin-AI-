package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class SeckillVoucherListener {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    // 监听正常队列QA
    @RabbitListener(queues = "QA")
    public void receivedA(Message message, Channel channel) throws Exception {
        try {
            String msg = new String(message.getBody());
            log.info("消费正常队列QA消息：{}", msg);
            VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);

            // 核心：只调用handleVoucherOrder（走分布式锁+事务+扣库存）
            voucherOrderService.handleVoucherOrder(voucherOrder);

            // 手动确认消息
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            log.info("订单{}消费成功", voucherOrder.getId());
        } catch (Exception e) {
            log.error("消费QA队列失败", e);
            // 拒绝消息并重回队列
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    // 监听死信队列QD
    @RabbitListener(queues = "QD")
    public void receivedD(Message message, Channel channel) throws Exception {
        try {
            String msg = new String(message.getBody());
            log.info("消费死信队列QD消息：{}", msg);
            VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);

            // 兜底处理
            voucherOrderService.handleVoucherOrder(voucherOrder);

            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            log.info("死信订单{}消费成功", voucherOrder.getId());
        } catch (Exception e) {
            log.error("消费QD队列失败", e);
            // 不再重试
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
        }
    }
}