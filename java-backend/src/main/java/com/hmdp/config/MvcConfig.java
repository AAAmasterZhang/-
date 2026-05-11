package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig  implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;    // 注入Redis模板

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //将Redis模板传递给登录拦截器
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
                "/shop/**",
                "/blog/hot",
                "/voucher/**",
                "/shop-type/**",
                "/upload/**",
                "/user/code",
                "/user/login"

        ).order(1); //后执行

        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);  //越小越先执行
    }
}
