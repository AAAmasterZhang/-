package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;




    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 写入缓存
     * @param key 缓存键
     * @param value 缓存值
     * @param time 过期时间
     * @param timeUnit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);

    }


    /**
     * 写入缓存，逻辑过期
     * @param key 缓存键
     * @param value 缓存值
     * @param time 过期时间
     * @param timeUnit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //设置过期时间，转换成秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        //写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }


    /**
     * 缓存穿透查询
     * @param keyPrefix 缓存键前缀
     * @param id 缓存键后缀  传入泛型，因为不确定id类型
     * @param type 缓存值类型
     * @param dbFallback 数据库查询函数，
     * 传入的是function，有输入有返回值。因为传入值和返回值类型都无法确定，所以只能通过调用者传入查询函数
     * @param time 过期时间
     * @param timeUnit 时间单位，过期时间不写死，由调用者确定
     * @param <R> 缓存值类型
     * @param <ID> 缓存键后缀类型
     * @return 缓存值
     * 泛型使用，现在<>确定需要用到的泛型
     * 访问修饰符 <泛型参数1, 泛型参数2, ...> 返回值类型 方法名(参数列表) {
     *     // 方法体
     * }
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;    //前缀拼接id，作为缓存键
        //从缓存中查询
        String json = stringRedisTemplate.opsForValue().get(key);

        //如果存在，返回
        if(StrUtil.isNotBlank(json)) {
            //StrUtil.isNotBlank(json) 如果json是null,空字符串，空白字符（空格，制表符，换行符等）都返回false
            //只有当json包含非空白实际内容时，才返回true
            return JSONUtil.toBean(json, type);
        }

        //这里只剩下null和空字符串两种情况。如果是空字符串（使用空字符串解决缓存穿透）
        if(json != null) {
            return null;
        }

        //使用传入的函数进行数据库查询
        R r = dbFallback.apply(id);

        //如果数据库不存在，就写入空值进redis
        if(r == null){
           stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
           return null;
        }

        //如果数据库存在，写入缓存
        this.set(key, r, time, timeUnit);

        return r;

    }

    /**
     * 互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        //对应setnx命令，设置成功返回true，失败返回false
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

     /**
      * 释放互斥锁
      * @param key
      */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期查询
     * @param prefix 缓存键前缀
     * @param id 缓存键后缀  传入泛型，因为不确定id类型
     * @param type 缓存值类型
     * @param dbFallback 数据库查询函数，
     * 传入的是function，有输入有返回值。因为传入值和返回值类型都无法确定，所以只能通过调用者传入查询函数
     * @param time 过期时间
     * @param timeUnit 时间单位，过期时间不写死，由调用者确定
     * @param <R> 缓存值类型
     * @param <ID> 缓存键后缀类型
     * @return 缓存值
     */
    public <R,ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = prefix + id;
        //从缓存中获取
        String json = stringRedisTemplate.opsForValue().get(key);

        //不存在 返回错误
        if(StrUtil.isNotBlank(json)) {
            return null;
        }

        //存在，反序列化，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            //未过期，返回查询
            return r;
        }

        //过期，获取互斥锁
        String lockKey = "lock:shop" + id;
        boolean isLock = tryLock(lockKey);

        //获取成功
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //根据id查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入缓存
                    this.setWithLogicalExpire(key, r, time, timeUnit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }
                finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回查询结果
        return r;
    }




}
