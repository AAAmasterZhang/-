package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注或取关
     * @param followUserId 关注用户id
     * @param isFollow 是否关注
     * @return 结果
     */
    @PutMapping("/{Id}/{isFollow}")
    public Result follow(@PathVariable("Id") Long followUserId, @PathVariable("isFollow") boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 查询是否关注
     * @param followUserId 关注用户id
     * @return 结果
     */
    @GetMapping("/or/not/{Id}")
    public Result isFollow(@PathVariable("Id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 共同关注
     */
    @GetMapping("/common/{Id}")
    public Result followCommons(@PathVariable("Id") Long Id) {
        return followService.followCommons(Id);
    }

}
