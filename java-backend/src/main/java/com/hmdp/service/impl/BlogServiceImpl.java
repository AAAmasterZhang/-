package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Autowired
    private RestTemplate restTemplate;

    private static final String PYTHON_UPLOAD_BLOG_URL = "http://localhost:8000/api/rag/upload_blog";


    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }

        //查询blog有关用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        //查询当前登录的用户是否点赞过blog，如果点赞过则前端高亮显示
        Long nowUserId = UserHolder.getUser().getId();
        String key = "blog:liked:" +id;
        Double score = stringRedisTemplate.opsForZSet().score(key, nowUserId.toString());
        blog.setIsLike(BooleanUtil.isTrue(score != null));


        return Result.ok(blog);
    }

    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();

        //判断是否点赞  查询是否有分数
        String key = "blog:liked:" +id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //没有点赞，1.数据库点赞数加一 2.保存到redis的zset里
            boolean isLike = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isLike){
                //成功则添加到redis的zset里,分数是当前时间戳
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else {
            //已点赞，1.数据库点赞数减一 2.从redis的zset里移除
            boolean isUnlike = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isUnlike){
                //成功则从redis的set里移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    //查询top5点赞的用户
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" +id;

        //查询top5
        Set<String> top5 = stringRedisTemplate.opsForZSet().reverseRange(key, 0, 4);
        if (top5 == null||top5.isEmpty()){
            return Result.ok(Collections.emptyEnumeration());
        }

        //解析其中用户
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idsStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id,"+idsStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);

    }

    /**
     * 新增笔记，并推送给粉丝
     * @param blog 笔记
     * @return 笔记id
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());   //写博客的用户就是当前登陆的用户

        //保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }

        // ========== 新增核心逻辑：调用Python的upload_blog接口同步到向量库 ==========
        try {
            // 1. 构造请求头（JSON格式）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 2. 构造请求体（匹配Python的BlogUploadRequest模型）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("blog_id", blog.getId().toString());  // 用数据库生成的博客ID作为唯一标识
            requestBody.put("title", blog.getTitle());            // 博客标题（假设Blog实体有title字段）
            requestBody.put("content", blog.getContent());        // 博客内容（假设Blog实体有content字段）
            requestBody.put("comments", new ArrayList<>());       // 新增博客时暂无评论，传空列表

            // 3. 构建请求实体
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            // 4. 调用Python接口
            Map<String, Object> response = restTemplate.postForObject(PYTHON_UPLOAD_BLOG_URL, requestEntity, Map.class);

            // 5. 打印响应（可选，用于调试）
            System.out.println("Python接口响应：" + response);
        } catch (Exception e) {
            // 接口调用失败不影响博客新增，仅打印异常（避免服务降级）
            e.printStackTrace();
            System.out.println("同步博客到向量库失败：" + e.getMessage());
        }

        //查询所有粉丝并发送
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        for(Follow follow : follows){
            Long userId = follow.getUserId();
            String key = "feed:" + userId;
            //每个粉丝创建一个zset集合作为收件箱，分数是当前时间戳
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        //返回id
        return Result.ok(blog.getId());

    }

    /**
     * 查询关注用户的博客
     * @param max 最后一条博客的id
     * @param offset 偏移量
     * @return 关注用户的博客列表
     */

    @Override
    public Result queryBlogofFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();

        //查询收件箱 zrevrangebyscore key max min offset count   按照score倒序查询，分数在max和min之间的元素，从offset开始，取count个
        String key = "feed:" +userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        };

        //解析数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;     //记录offset
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(Long.valueOf(typedTuple.getValue()));
            long time = typedTuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }

        //根据博客id查询博客
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id,"+StrUtil.join(",", ids)+")").list();

        //封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }


}
