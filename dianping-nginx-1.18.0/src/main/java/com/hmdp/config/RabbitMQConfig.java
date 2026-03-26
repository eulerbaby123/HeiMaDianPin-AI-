package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    // 1. 声明交换机X（和生产者一致）
    @Bean
    public DirectExchange exchangeX() {
        return new DirectExchange("X", true, false);
    }

    // 2. 声明正常队列QA（和消费者监听的一致）
    @Bean
    public Queue queueQA() {
        // 配置死信参数：死信交换机、死信路由键、过期时间
        return QueueBuilder.durable("QA")
                .deadLetterExchange("Y")
                .deadLetterRoutingKey("YD")
                .ttl(10000) // 10秒过期
                .build();
    }

    // 3. 声明死信交换机Y
    @Bean
    public DirectExchange exchangeY() {
        return new DirectExchange("Y", true, false);
    }

    // 4. 声明死信队列QD
    @Bean
    public Queue queueQD() {
        return QueueBuilder.durable("QD").build();
    }

    // 5. 绑定X→QA（路由键XA）：生产者发送的XA消息能到QA队列
    @Bean
    public Binding bindingXA(DirectExchange exchangeX, Queue queueQA) {
        return BindingBuilder.bind(queueQA).to(exchangeX).with("XA");
    }

    // 6. 绑定Y→QD（路由键YD）：死信消息路由到QD
    @Bean
    public Binding bindingYD(DirectExchange exchangeY, Queue queueQD) {
        return BindingBuilder.bind(queueQD).to(exchangeY).with("YD");
    }
}