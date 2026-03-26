
```markdown
## 解决缓存击穿：互斥锁（缓存重建互斥锁）

### 核心逻辑：queryWithLogicalExpire

```java
// CacheClient.java - queryWithLogicalExpire 核心逻辑
public <R, ID> R queryWithLogicalExpire(
    String keyPrefix, ID id, Class<R> type, 
    Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
    
    String key = keyPrefix + id;
    
    // 1. 读数据 key
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    if (StringUtils.isEmpty(shopJson)) {
        return null;
    }
    
    // 反序列化，拿到 expireTime
    RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
    R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
    LocalDateTime expireTime = redisData.getExpireTime();
    
    // 2. 判断逻辑是否过期
    if (expireTime.isAfter(LocalDateTime.now())) {
        return r; // 未过期，直接返回
    }
    
    // 3. 已过期，用锁 key 抢锁
    String lockKey = LOCK_SHOP_KEY + id;
    boolean isLock = tryLock(lockKey);
    
    if (isLock) {
        // 4. 抢到锁：开新线程重建数据 key
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                R r1 = dbFallback.apply(id);
                this.setWithLogicalExpire(key, r1, time, timeUnit);
            } finally {
                unlock(lockKey); // 重建完释放锁
            }
        });
    }
    
    // 5. 没抢到锁（或抢到锁的线程）都返回旧数据兜底
    return r;
}
```

### 辅助方法

```java
// 加锁：SET lock:shop:{id} "1" NX EX 10
private boolean tryLock(String key) {
    Boolean flag = stringRedisTemplate.opsForValue()
        .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}

// 释放锁：直接 delete（缺点：不验证是否是自己的锁）
private void unlock(String key) {
    stringRedisTemplate.delete(key);
}
```

### 数据结构

```java
// RedisData.java - 逻辑过期数据结构
public class RedisData {
    private LocalDateTime expireTime; // 逻辑过期时间
    private Object data;              // 真实数据
}
```

### 两个 key 对照

| key | 示例值 | TTL |
|-----|--------|-----|
| `cache:shop:1` | `{"data":{...}, "expireTime":"2026-03-26T10:00"}` | 无（永不过期） |
| `lock:shop:1` | `"1"` | 10s 自动消失 |

### 执行流程图

```
查询 cache:shop:1
    ↓
存在吗？
    ↓ 是
检查 expireTime
    ↓
过期了吗？
    ↓ 是
尝试获取 lock:shop:1
    ↓
抢到锁？
    ↓ 是                    ↓ 否
开新线程重建缓存            直接返回旧数据
    ↓
返回旧数据（兜底）
```
```

---

## 关键改进点

1. **代码块**使用三个反引号 + 语言标识（`java`、`markdown`）
2. **表格**使用标准Markdown语法（`|`分隔）
3. **标题层级**清晰（`##`、`###`）
4. **行内代码**用反引号包裹（`` `cache:shop:1` ``）

---

## 如果想更美观，可以再加这些

### 关键点总结

```markdown
### 💡 关键点总结

**为什么用逻辑过期而不是真实TTL？**
- 真实TTL会导致缓存击穿（大量请求同时打到DB）
- 逻辑过期让旧数据继续服务，新线程异步重建

**为什么释放锁不验证？**
- 这是简化版实现
- 生产环境应该用 UUID + Lua 脚本验证

**兜底策略**
- 无论是否抢到锁，都返回旧数据
- 保证服务可用性优先
```

---

