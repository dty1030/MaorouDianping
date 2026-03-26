好的！帮你整理成完整的Markdown格式：

```markdown
## Redisson 可重入锁

### 🎯 背景：它解决了 SimpleRedisLock 的哪些问题？

**上一个 SimpleRedisLock 的三个缺陷：**

1. ❌ 误删他人锁（Lua 能解决，但还有下面两个问题）
2. ❌ 不可重入：同一线程加锁两次会死锁
3. ❌ 没有续期：业务执行时间超过 TTL，锁自动释放，业务还没完

**✅ Redisson 全部解决了。**

---

### 💻 你项目里的代码

```java
// VoucherOrderServiceImpl.java

// 1. 获取锁对象（不是加锁，只是拿到锁的引用）
RLock lock = redissonClient.getLock("lock:order:" + userId + ":" + voucherId);

// 2. 尝试加锁
boolean locked = lock.tryLock(1L, -1, TimeUnit.SECONDS);
//                             ↑    ↑
//                         等待时间  持锁时间（-1 = 启用看门狗）

if (locked) {
    try {
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        return proxy.createVoucherOrder(voucherId);
    } finally {
        lock.unlock(); // 3. 释放锁
    }
}
```

---

### 📋 参数详解：tryLock(waitTime, leaseTime, unit)

| 参数 | 你传的值 | 含义 |
|------|---------|------|
| **waitTime** | `1L` | 抢不到锁最多等 1 秒，超时返回 `false` |
| **leaseTime** | `-1` | 不指定持锁时间，交给看门狗自动续期 |
| **unit** | `SECONDS` | 时间单位 |

**重要说明：**
- `leaseTime = -1` → 启用看门狗（推荐）
- `leaseTime = 30` → 固定持锁 30 秒，不续期

---

### 🔄 核心原理一：可重入（Hash 结构）

#### SimpleRedisLock 的问题

```
SET key value
只能存一个值，无法记录"同一线程加了几次锁"
```

#### Redisson 的解决方案：Redis Hash

```
key:   lock:order:1001:5
field: 线程唯一标识 (UUID + threadId)
value: 重入次数
```

**执行流程：**

```
第1次加锁：{ "uuid:threadId" : 1 }
第2次加锁：{ "uuid:threadId" : 2 }   ← 不会死锁，计数+1
释放1次： { "uuid:threadId" : 1 }
再释放1次：key 删除                  ← 计数归0才真正释放
```

**对应 Redis 命令：**
```redis
HINCRBY lock:order:1001:5 "uuid:threadId" 1
```

**原子性保证：**
- 整个加锁/释放逻辑用 **Lua 脚本** 保证原子性
- 不存在 SimpleRedisLock 里"查+删非原子"的问题

---

### 🐕 核心原理二：看门狗自动续期（WatchDog）

**当 `leaseTime = -1` 时，Redisson 启动看门狗：**

```
加锁成功
    ↓
看门狗启动，默认锁有效期 30s
    ↓
每隔 10s 检查一次：业务还在执行吗？
    ↓ 还在
重新设置过期时间为 30s（续期）
    ↓
业务完成 → unlock() → 看门狗停止
```

**关键特性：**
- ✅ 只要业务线程还活着，锁就不会过期
- ✅ 业务线程挂了，看门狗也跟着停
- ✅ 锁 30s 后自动释放，不会死锁

**续期时间计算：**
```
默认锁有效期：30s
续期间隔：30 / 3 = 10s
每次续期到：30s
```

---

### 🔒 核心原理三：Lua 脚本保证原子性

**Redisson 的所有操作都是 Lua 脚本：**

| 操作 | 说明 |
|------|------|
| 加锁 | Lua 脚本执行 HINCRBY + PEXPIRE |
| 续期 | Lua 脚本执行 PEXPIRE |
| 释放 | Lua 脚本执行 HINCRBY -1 + DEL |

**优势：**
- 完全原子性
- 杜绝 SimpleRedisLock 里"查+删非原子"的问题

---

### 🔑 锁 key 的设计

```
lock:order:{userId}:{voucherId}
```

**设计思路：**

| 维度 | 说明 |
|------|------|
| **粒度** | 用户 + 券，不是只锁用户 |
| **优点** | 不同用户抢不同券互不影响，并发性更好 |
| **示例** | `lock:order:1001:5` → 用户1001抢券5 |

**对比：**

```
❌ 只锁用户：lock:order:{userId}
   → 用户1001抢券5时，不能同时抢券6，并发性差

✅ 锁用户+券：lock:order:{userId}:{voucherId}
   → 用户1001可以同时抢券5和券6，并发性好
```

---

### 💬 面试常见追问

#### Q1：leaseTime 传正数和 -1 有什么区别？

**A：**

| leaseTime | 行为 | 看门狗 | 适用场景 |
|-----------|------|--------|----------|
| **正数**（如 30） | 固定持锁 30 秒，到时间自动释放 | ❌ 不启动 | 业务执行时间确定 |
| **-1** | 看门狗自动续期 | ✅ 启动 | 业务执行时间不确定（推荐） |

**推荐做法：**
- 生产环境用 `-1`，避免业务时间估算不准导致锁提前释放

---

#### Q2：看门狗默认多久续一次？

**A：**

```
默认锁有效期：30s
续期间隔：30 / 3 = 10s
每次续期到：30s
```

**源码常量：**
```java
// Redisson 源码
private long lockWatchdogTimeout = 30 * 1000; // 30秒
```

---

#### Q3：Redisson 怎么保证可重入是同一个线程？

**A：**

**field 的组成：**
```
UUID + ":" + threadId
```

**唯一性保证：**

| 部分 | 作用 | 保证范围 |
|------|------|----------|
| **UUID** | Redisson 客户端启动时生成 | 不同 JVM 实例不冲突 |
| **threadId** | `Thread.currentThread().getId()` | 同一 JVM 内线程唯一 |

**示例：**
```
不同服务器实例：
服务器A：uuid-aaa:123
服务器B：uuid-bbb:123  ← threadId 相同，但 UUID 不同

同一服务器不同线程：
线程1：uuid-aaa:123
线程2：uuid-aaa:456  ← UUID 相同，但 threadId 不同
```

---

### 📊 SimpleRedisLock vs Redisson 对比

| 特性 | SimpleRedisLock | Redisson |
|------|----------------|----------|
| **可重入** | ❌ 不支持 | ✅ Hash 结构支持 |
| **自动续期** | ❌ 无 | ✅ WatchDog 机制 |
| **误删锁** | ⚠️ 需手动 Lua | ✅ 内置 Lua |
| **等待重试** | ❌ 无 | ✅ 支持 waitTime |
| **原子性** | ⚠️ 需手动保证 | ✅ 全部 Lua 脚本 |
| **生产可用性** | ❌ Demo 级别 | ✅ 企业级 |

---

### 🎯 总结

**Redisson 三大核心优势：**

```
1. Hash 结构实现可重入
   → 同一线程可以多次加锁，不会死锁

2. WatchDog 自动续期
   → 业务执行多久，锁就续多久，不会超时

3. Lua 脚本保证原子性
   → 加锁、续期、释放全部原子操作，杜绝误删
```

**使用建议：**
- ✅ 生产环境直接用 Redisson
- ✅ `leaseTime` 传 `-1` 启用看门狗
- ✅ 锁粒度根据业务需求设计（用户、订单、商品等）
- ✅ 记得在 `finally` 块中释放锁
```

---

## 额外补充（可选）

如果想让笔记更完整，可以再加上：

```markdown
### 🔧 完整代码示例

```java
@Service
public class VoucherOrderServiceImpl implements IVoucherOrderService {
    
    @Resource
    private RedissonClient redissonClient;
    
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        
        // 获取锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId + ":" + voucherId);
        
        // 尝试加锁
        boolean isLock = false;
        try {
            isLock = lock.tryLock(1L, -1, TimeUnit.SECONDS);
            if (!isLock) {
                return Result.fail("不允许重复下单");
            }
            
            // 获取代理对象（事务生效）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
            
        } catch (InterruptedException e) {
            return Result.fail("系统异常");
        } finally {
            if (isLock) {
                lock.unlock();
            }
        }
    }
    
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单逻辑
        Long userId = UserHolder.getUser().getId();
        
        // 查询订单
        int count = query().eq("user_id", userId)
                          .eq("voucher_id", voucherId)
                          .count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次");
        }
        
        // 扣减库存
        boolean success = seckillVoucherService.update()
            .setSql("stock = stock - 1")
            .eq("voucher_id", voucherId)
            .gt("stock", 0)  // 乐观锁
            .update();
            
        if (!success) {
            return Result.fail("库存不足");
        }
        
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        
        return Result.ok(orderId);
    }
}
```

### 🐛 常见问题排查

#### 问题1：锁一直获取不到

**可能原因：**
- 上一次加锁后没有释放（没写 `finally`）
- 业务代码抛异常，unlock 没执行

**解决方案：**
```java
try {
    lock.tryLock();
    // 业务逻辑
} finally {
    lock.unlock();  // 一定要在 finally 中释放
}
```

---

#### 问题2：IllegalMonitorStateException

**错误信息：**
```
attempt to unlock lock, not locked by current thread
```

**原因：**
- 在没有加锁的情况下调用 unlock
- 或者在不同线程中释放锁

**解决方案：**
```java
if (isLock) {
    lock.unlock();  // 只有加锁成功才释放
}
```
```

---

直接复制就能用！需要我帮你把这个整合到你的 `Redis.md` 文件中，和之前的内容一起吗？