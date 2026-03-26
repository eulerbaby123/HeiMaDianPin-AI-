package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;

    //组合
    private Object data;
}
