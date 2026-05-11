package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注或取关
     * @param followUserId 关注用户id
     * @param isFollow 是否关注
     * @return 结果
     */
    @Override
    public Result follow(Long followUserId, boolean isFollow) {

        //获取用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        //判断关注还是取关
        if(isFollow){
            //关注,新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess  = save(follow);
            if(isSuccess){
                //关注成功,添加到redis，集合类型，键是当前用户id，值是关注用户id
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            //取关
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            //取关成功,从redis中移除
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();


    }

    /**
     * 查询是否关注
     * @param followUserId 关注用户id
     * @return 结果
     */
    @Override
    public Result isFollow(Long followUserId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();

        //查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);

    }

    /**
     * 查询共同关注
     * @param id 关注用户id
     * @return 结果
     */
    @Override
    public Result followCommons(Long id) {

        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        //求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);

        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //解析集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        //查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);


    }
}
