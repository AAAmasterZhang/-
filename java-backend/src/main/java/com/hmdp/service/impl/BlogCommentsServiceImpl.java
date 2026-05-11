package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Override
    public Result addComment(BlogComments comments) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");

        comments.setUserId(user.getId());
        comments.setCreateTime(LocalDateTime.now());
        comments.setUpdateTime(LocalDateTime.now());
        comments.setStatus(false);

        boolean saved = save(comments);
        if (!saved) return Result.fail("评论失败");

        blogService.update().setSql("comments = comments + 1").eq("id", comments.getBlogId()).update();
        return Result.ok();
    }

    @Override
    public Result queryCommentsByBlogId(Long blogId) {
        // 1. 查询一级评论
        List<BlogComments> comments = this.query()
                .eq("blog_id", blogId)
                .eq("parent_id", 0L)
                .orderByDesc("create_time")
                .list();

        // 关键修复点：确保返回的是标准 ArrayList，且不为 null
        if (comments == null || comments.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }

        // 2. 补全用户信息和回复内容
        for (BlogComments comment : comments) {
            // 补充作者信息
            User user = userService.getById(comment.getUserId());
            if (user != null) {
                comment.setUserIcon(user.getIcon());
                comment.setUserName(user.getNickName());
            }

            // 查询回复
            List<BlogComments> replies = this.query()
                    .eq("parent_id", comment.getId())
                    .orderByAsc("create_time")
                    .list();
            // 关键修复点：手动封装 replies 避免特殊 Enumeration 类型
            comment.setReplies(replies == null ? new ArrayList<>() : new ArrayList<>(replies));
        }

        // 关键修复点：手动封装返回列表
        return Result.ok(new ArrayList<>(comments));
    }

    @Override
    public Result deleteComment(Long id) {
        // 获取评论信息
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在");
        }

        // 检查是否是当前用户的评论
        UserDTO user = UserHolder.getUser();
        if (!comment.getUserId().equals(user.getId())) {
            return Result.fail("无权删除他人评论");
        }

        // 删除评论
        boolean deleted = removeById(id);
        if (!deleted) {
            return Result.fail("删除失败");
        }

        // 更新博客评论数
        blogService.update()
                .setSql("comments = comments - 1")
                .eq("id", comment.getBlogId())
                .update();

        return Result.ok();
    }
}
