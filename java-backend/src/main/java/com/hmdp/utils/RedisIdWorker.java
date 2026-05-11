package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1767225600000L;  //2026-01-01 00:00:00

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        //生成时间戳，当前时间减去开始时间，单位毫秒
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;

        //生成序列号
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增，每天一个key，当有新的订单，就在当天这个key下自增
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + data);

        //拼接 位运算
        return timestamp << 32 | count;
    }



}
