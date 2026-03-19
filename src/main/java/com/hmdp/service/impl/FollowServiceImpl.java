package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 毛肉
 * @since 2026/3/20
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //1. 判断是否是关注还是取关
        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
            //同时写入Redis
            //先创建一个Key(当前用户的关注列表)

            stringRedisTemplate.opsForSet().add(key, String.valueOf(followUserId));
        }
        else {
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id",followUserId));
            stringRedisTemplate.opsForSet().remove(key, String.valueOf(followUserId));
        }
        return Result.ok();
    }

    /**
     * 查询当前登录用户是否关注了用户followUserId
     * @param id
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        //当前登录用户ID
        Long userId = UserHolder.getUser().getId();
        //
        boolean isFollow = count(new QueryWrapper<Follow>()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)) > 0;

        return Result.ok(isFollow);
    }
}
