package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public Result sendCode(String phone, HttpSession session) {
        //校验手机号是否符合格式，调用已有的工具类去校验手机号码
        if(RegexUtils.isPhoneInvalid(phone)){
            // 1. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

/*        //保存验证码到session
        session.setAttribute("code", code);*/
        //保存验证码到redis，添加前缀加有效期
        stringRedisTemplate.opsForValue().set("login:code:" + phone, code, 5, TimeUnit.MINUTES);

        //发送验证码
        log.debug("发送验证码成功，验证码为：{}", code);
        return Result.ok();
    }


    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号是否符合格式，调用已有的工具类去校验手机号码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 1. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //校验验证码
//        Object cacheCode = session.getAttribute("code");    //获取session中的验证码
        //从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get("login:code:" + phone);
        String code = loginForm.getCode();    //获取登录提交的验证码
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("验证码错误");
        }

        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //判断用户是否存在
        if (user == null) {
            //不存在，注册
            User newUser = new User();
            newUser.setPhone(phone);
            newUser.setPassword(RandomUtil.randomNumbers(6));
            //保存用户到数据库
            save(newUser);
            user = newUser;

        }

        //保存用户信息到session
/*        // 1. 创建UserDTO对象
        UserDTO userDTO = new UserDTO();
        // 2. 将User属性复制到UserDTO
        BeanUtils.copyProperties(user, userDTO);
         session.setAttribute("user", userDTO);*/

        //保存用户信息到redis
        //1.生成随机令牌token
        String token = UUID.randomUUID().toString(true);

        //2.将用户信息转为hash存储   将user转换成map，然后用putall存进去
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        //new HashMap<>()：指定转换后的Map类型
        //CopyOptions.create()...：转换配置选项，用于定制转换规则
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)       //忽略null值，不转换为null字符串
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));    //将所有字段值转换为字符串
        stringRedisTemplate.opsForHash().putAll("login:user:" + token, userMap);
        //设置有效期
        stringRedisTemplate.expire("login:user:" + token, 30, TimeUnit.MINUTES);



        //3.返回token
        return Result.ok(token);
    }

}
