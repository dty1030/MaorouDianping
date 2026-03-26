package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    IBlogService blogService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    IUserService userService;

    @Resource
    IFollowService followService;

    /**
     * 查询点赞排行榜
     * 按点赞时间进行排序,返回Top5的用户
     * @param id
     * @return
     */
    public Result queryBlogLikes(Long blogId){

        String key = RedisConstants.BLOG_LIKED_KEY + blogId;

        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key,0, 4 );
        if (top5 == null || top5.isEmpty())return Result.ok(Collections.emptyList());
//        List<UserDTO> userDTOS= top5.stream()
//                .map(Long::valueOf)
//                .map(userService::getById)
//                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
        List<Long> userIds = top5.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        //拼接SQL
        String res = StrUtil.join(",", userIds);
        List<User> users = userService.query().in("id", userIds)
                .last("ORDER BY FIELD(id, " + res + ")")
                .list();
        List<UserDTO> userDTOS = BeanUtil.copyToList(users, UserDTO.class);


        return Result.ok(userDTOS);
    }

    /**
     * 判断当前登录用户是否点赞过Blog
     * @param blog
     */
    public void isBlogLiked(Blog blog){

        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询当前登录用户是否点赞过blog--
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        blog.setIsLike(score != null);

    }
    
    

    public Result queryBlogById(Long id) {
        //1. 查询blog
        Blog blog = getById(id);
        if (blog == null)return Result.fail("笔记不存在");
        //2. 查询blog有关的用户
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotblog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    public void likeBlog(Long id){
        //1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 判断当前用户是否已经点赞过
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double likeScore = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //获取当前时间戳
        double timeStamp = System.currentTimeMillis();
        //如果未点赞过----
        if(likeScore == null){
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess)stringRedisTemplate.opsForZSet().add(key, userId.toString(), timeStamp);
        }
        else {
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess)stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
    }

    @Override
    public boolean saveBlog(Blog blog) {
        if (!isSuccess)return Result.fail("保存博客失败");
        //1. 查询博客发布者的followers
        List<Follow> follows= followService.list(new QueryWrapper<Follow>()
                .eq("follow_user_id", blog.getUserId()));
//        List<Follow> follows1 = followService.query()
//                .eq("follow_user_id", blog.getUserId()).list();
        for(Follow follow: follows){
            //得到粉丝ID
            Long userId = follow.getUserId();
            //
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(blog.getId()), System.currentTimeMillis());

        }
        return false;
    }


}
