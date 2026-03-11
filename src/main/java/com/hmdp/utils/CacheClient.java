package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**工具类
 * 处理缓存
 */

@Component
@Slf4j
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        //用户传入时传的是任意类型的参数, 我们需要自己序列化
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 逻辑过期
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //写入
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }

    /**
     * 缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit){

        String key = keyPrefix + id;
        //1. 从Redis中查询商铺缓存
        //得到Redis字符串
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为空
        if (StrUtil.isNotBlank(json)){
            //Redis中存在, 则直接返回并转换Redis字符串为Java对象
            return JSONUtil.toBean(json, type);
        }
        //因为isNotBlank("")会返回false, 所以判断是否是空值
        if (json != null){
            return null;
        }
        //4. Redis中不存在, 查询数据库
        R r = dbFallback.apply(id);
        //5. 数据库也不存在, 报错
        if (r == null){
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在, 存入Redis
        this.set(key, r, time, timeUnit);
        //返回
        return r;
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     *
     * @param id id
     * @return {@link Shop}
     */
    public  <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        //从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StringUtils.isEmpty(shopJson)) {
            //不存在返回空
            return null;
        }
        //命中 反序列化JSON对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject)redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //已过期, 需要缓存重建--
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //获取锁成功
        if (isLock){
            //开启独立线程, 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key, r1, time, timeUnit);
                } catch (Exception e){
                    throw new RuntimeException(e);
                }
                finally {
                    unlock(lockKey);
                }

        });
    }
        return r;
    }
    //获取锁
    private boolean tryLock(String key){

        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁(删除)
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }






}
