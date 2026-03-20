package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
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
 * @author 毛肉
 * @since 2026/3/20
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

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
     * 查询当前登录用户是否关注了用户---followUserId
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

    @Override
    public Result followCommons(Long id) {
        //1 . 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        //2. 求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty())return Result.ok(Collections.emptyList());

        //3. 解析
        List<Long> ids= intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.toBean(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }
}
