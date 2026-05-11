package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
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
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 添加评论
     * @param comments 评论信息
     * @return 结果
     */
    @PostMapping
    public Result addComment(@RequestBody BlogComments comments) {
        return blogCommentsService.addComment(comments);
    }

    /**
     * 查询博客评论列表
     * @param blogId 博客id
     * @return 评论列表
     */
    @GetMapping("/of/blog")
    public Result queryCommentsByBlogId(@RequestParam("blogId") Long blogId) {
        return blogCommentsService.queryCommentsByBlogId(blogId);
    }

    /**
     * 删除评论
     * @param id 评论id
     * @return 结果
     */
    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }

}
