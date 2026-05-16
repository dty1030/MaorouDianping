package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Session;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 毛肉
 * @since 2026/5/5
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号码.
        if (RegexUtils.isPhoneInvalid(phone)){
            //2. 如果不符合手机号码格式，返回错误信息
            return Result.fail("手机号不符合格式");
        }
        //3. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 保存验证码到Session
        //session.setAttribute("code", code);
        //5. 发送验证码
        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(key, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码成功, 验证码为: " + code);
        //6. 返回Result.ok()
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //2. 如果不符合手机号码格式，返回错误信息
            return Result.fail("手机号不符合格式");
        }
        //3. 取出验证码
        //Object cacheCode = session.getAttribute("code");
        Object cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        //4. 取出用户输入的验证码
        String code = loginForm.getCode();
        //5. 验证
        if (cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        //6. 根据手机号码查用户(去数据库查询)
        User user = query().eq("phone", phone).one();
        //7. 判断用户是否存在--
        if (user == null){
            //8. 如果用户不存在, 创建用户并且保存
            user = createUserWithPhone(phone);
        }
        //9. 保存用户信息到Session中
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token = UUID.randomUUID().toString(true);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) ->
                                fieldValue == null ? null : fieldValue.toString()));
        //顶号
        String oldToken = stringRedisTemplate.opsForValue()
                .get("login:user:phone:" + phone);
        //
        if (oldToken != null){
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + oldToken);
        }
        //存登录态用户信息
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置TTL
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);


        //设置反向索引
        stringRedisTemplate.opsForValue().set("login:user:phone:" + phone, token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone){
        //创建用户
        User user = new User();
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        user.setPhone(phone);
        //2. 保存用户
        save(user);
        return user;
    }

    @Override
    public Result logout(String token){
        if (StrUtil.isBlank(token))return Result.ok();
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        //String phone = stringRedisTemplate.opsForHash().get(tokenKey, "phone").toString();
        //清除主索引
        stringRedisTemplate.delete(tokenKey);
        return Result.ok();
    }
}
