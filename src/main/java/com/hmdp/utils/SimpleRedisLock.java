package com.hmdp.utils;


import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


/**
 * 实现Redis分布式锁
 */
public class SimpleRedisLock implements ILock{

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private String name;
    private static final String KEY_PREFIX = "lock";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate){
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean tryLock(long timeoutSec){
        //获取当前线程ID(标识)
        long threadId = Thread.currentThread().getId();
        //1. 获取锁 (Set NX)
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    public void unlock(){
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }


//    //lua脚本进行解锁
//    public void unlock(){
//        //
//        stringRedisTemplate.execute(
//                UNLOCK_SCRIPT,
//                Collections.singletonList(KEY_PREFIX + name),
//                ID_PREFIX + Thread.currentThread().getId()
//        );
//    }


}
