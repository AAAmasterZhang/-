package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //这个拦截器只用来刷新token有效期，直接放行就可以
        //如果直接调用 token.isEmpty() 而 token 为 null，会抛出 NullPointerException 异常，所以要先判断 token 是否为 null
        String token = request.getHeader("authorization");
        if (token == null || token.isEmpty()) {
            return true;
        }

        //基于token获取用户信息
        String key = "login:user:" + token;
        //entries = 获取整个 Hash 结构的所有键值对，返回 Map
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        //判断这个token是否有效
        if (userMap.isEmpty()) {
            return true;
        }

        //将查询到的hash数据转化为userdto对象。false是不忽略转换过程中的错误，有异常还是要抛出
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //存在，保存到ThreadLocal
        UserHolder.saveUser(userDTO);

        //刷新token有效期
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);

        //放行
        return true;



    }

     @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }






}
