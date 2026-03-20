package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 毛肉
 * @since 2026/3/19
 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);
    Result queryHotblog(Integer current);
    Result queryBlogLikes(Long blogId);

    void likeBlog(Long id);

    boolean saveBlog(Blog blog);
}
