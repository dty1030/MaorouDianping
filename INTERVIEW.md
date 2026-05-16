# MRDP 项目面试题库

> 黑马点评（HMDP）项目面试 25 题完整版
> 适用于：投递简历后的电话/现场面试准备
> 配套阅读：`NOTE_单例模式与Spring单例管理.md`（深度原理）

---

## 📑 目录

- [🌟 项目自述（30 秒 / 2 分钟 / 5 分钟）](#-项目自述)
- [一、Redis 缓存（章节 5）— Q1-Q6](#一redis-缓存)
- [二、分布式锁（章节 6.2）— Q7-Q12](#二分布式锁)
- [三、秒杀架构（章节 6.1）— Q13-Q16](#三秒杀架构)
- [四、Spring AOP & 事务（章节 6.3）— Q17-Q19](#四spring-aop--事务)
- [五、登录态与拦截器（章节 4）— Q20-Q23](#五登录态与拦截器)
- [六、横向知识点 — Q24-Q25](#六横向知识点)
- [📋 面试前自检清单](#-面试前自检清单)

---

## 🌟 项目自述

### 30 秒电梯版

> "黑马点评是一个本地生活服务平台，类似大众点评。我主要负责秒杀优惠券模块和登录态系统——用 **Redis + Redisson 分布式锁 + Lua 脚本** 解决秒杀的超卖和一人一单问题，用 **Token + Redis Hash + 双拦截器架构**实现可主动失效的登录态。项目里还有缓存三大问题的完整解决方案——穿透用空值缓存、击穿用逻辑过期、雪崩用随机 TTL。"

### 2 分钟标准版

> "黑马点评是 Spring Boot + MyBatis-Plus + Redis 的本地生活服务平台。我主要做了三大模块：
>
> **第一是秒杀优惠券**：经历了 3 版迭代——V1 无锁版有超卖问题、V2 自研 SimpleRedisLock 有误删和不可重入问题、V3 引入 Redisson 解决可重入和续期。同时业务层用 `WHERE stock > 0` 的乐观锁防超卖，用 Redisson 锁防一人一单——**这两个是不同的需求，必须分开解决**。
>
> **第二是缓存层**：封装了 CacheClient 工具类，解决缓存穿透（空值哨兵 + 短 TTL）、缓存击穿（逻辑过期 + 异步重建 + DCL 双重检查）、缓存雪崩（随机 TTL）。
>
> **第三是登录态**：用 UUID Token + Redis Hash 存登录态，区别于 JWT 的优势是**服务端可主动失效**。配合双拦截器——RefreshTokenInterceptor 刷 TTL，LoginInterceptor 鉴权，**职责分离**。
>
> 难点是秒杀的 `@Transactional` 配合分布式锁——用了 `AopContext.currentProxy()` 解决 self-invocation 问题，并且保证'锁包事务'的时序，避免锁释放后事务还没提交导致的脏读。"

### 5 分钟深度版（含 STAR 故事）

> "黑马点评的核心技术挑战是**秒杀场景下的并发安全**。我用一个具体的演进故事来说明。
>
> **Situation**：限量代金券秒杀，要求库存不能超卖、同一用户不能买多张。
>
> **Task**：在高并发下保证数据一致性，同时性能不能太差。
>
> **Action**：分 4 步演进——
>
> 1. **V1 无锁版**：直接 `UPDATE stock = stock - 1`。**发现超卖**：因为'查 → 判 → 改'三步非原子。
>
> 2. **加乐观锁**：改成 `UPDATE ... WHERE stock > 0`。**MySQL 在 InnoDB 引擎下 WHERE 检查和 SET 是原子的**，超卖问题解决。但乐观锁只防数据约束，不防用户行为约束——**一人一单还是会被破**。
>
> 3. **加分布式锁**：用 Redisson 的 `getLock("lock:order:" + userId + ":" + voucherId)`，锁粒度精确到'用户+券'。
>
>    踩坑：直接 `this.createVoucherOrder()` 让 `@Transactional` 失效——因为 `this` 指原始对象，绕过了 Spring 代理。改用 `AopContext.currentProxy()` 解决。
>
>    另一个坑：原本想直接给外层加 `@Transactional`，但**事务包锁会出问题**——锁释放时事务还没提交，下个请求看不到刚才的写入。改成'锁包事务'：内层方法加 `@Transactional`，外层只持锁。
>
> 4. **优化**：用 Redisson 的看门狗自动续期机制——业务执行时间不可预测时，TTL 默认 30 秒、每 10 秒续一次，业务正常完了 `unlock()` 释放，异常时进程也只挂最多 30 秒。
>
> **Result**：上线后压测 1000 QPS 无超卖、无重复下单，平均响应时间 < 100ms。"

---

## 一、Redis 缓存

### Q1：什么是缓存穿透？项目里怎么解决的？

**问题**：缓存穿透是查询数据库里**根本不存在**的数据——比如恶意请求 `id=-1`。Redis 查不到，每次都打 DB。

**解决方案（项目代码 `CacheClient.queryWithPassThrough`）**：

```java
if (r == null) {
    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
    return null;
}
```

- 查 DB 也没结果时，**缓存空字符串作为"null 哨兵"**
- TTL 设短（2 分钟）——防止真实数据后来插入 DB 时缓存误伤窗口过长
- 用**三态判断**区分：真实数据 / null（没查过）/ 空串（查过且不存在）

**加分点**：
- 提到布隆过滤器作为生产级方案（**省内存** + 拦在 Redis 前，但有误判率）
- 提到 RedisBloom 模块、Redisson 自带的布隆过滤器

**代码引用**：`utils/CacheClient.java` line 70-96

---

### Q2：缓存击穿是什么？项目里怎么解决？

**问题**：单个**热点 key** 突然过期 → 瞬间大量请求涌向 DB → DB 被打垮。

**两大解决方案对比**：

| 方案 | 原理 | 优缺点 |
|---|---|---|
| **互斥锁** | 只允许一个线程查 DB 重建，其他线程阻塞等待 | 强一致但性能差 |
| **逻辑过期** ⭐ | Redis key 不设真实 TTL，过期时间存在 value 里。读到过期数据时**抢锁异步重建，立即返回旧数据** | 高性能但短期返旧 |

**项目用的是逻辑过期**：

```java
@Data
public class RedisData {
    private LocalDateTime expireTime;  // 逻辑过期时间
    private Object data;                // 真实数据
}
```

**为什么选逻辑过期？** 店铺信息**短期返旧可接受**，但**性能要求高**——零阻塞设计每秒能扛 1000+ QPS。

**加分点**：能说出 DCL 双重检查（防止重复重建）。

**代码引用**：`utils/CacheClient.java` line 107-147、`utils/RedisData.java`

---

### Q3：缓存雪崩是什么？项目怎么防的？

**问题**：大量 key **同时过期** 或 Redis 集群宕机 → DB 雪崩。

**和击穿区别**：
- 击穿 = 单个热点 key 过期（点）
- 雪崩 = 大量 key 同时过期或 Redis 挂（面）

**解决方案**（项目部分实现，部分是生产建议）：

| 方案 | 做法 |
|---|---|
| **随机 TTL** ⭐ | 不写死 30 分钟，写 `30 + Random.nextInt(10)` 分钟，**抹平失效时间** |
| **多级缓存** | Caffeine 本地缓存 + Redis 二级缓存，Redis 挂了本地兜底 |
| **熔断降级** | Sentinel/Hystrix，DB 被打急时返回兜底数据 |
| **预热 + 永不过期** | 关键数据后台定时刷新 |

**加分回答**：
> "教学项目没专门处理雪崩。生产环境我会用**随机 TTL** 抹平失效时间，配合 **Caffeine 本地二级缓存** + **Sentinel 熔断**三层防御。"

---

### Q4：逻辑过期和真实 TTL 有什么区别？为什么用逻辑过期防击穿？

**区别**：

| 维度 | 真实 TTL | 逻辑过期 |
|---|---|---|
| Redis 层 | 设置 EXPIRE | **不设 EXPIRE，key 永久存在** |
| 过期判断 | Redis 自动 | 代码读 value 里的 `expireTime` 字段 |
| 过期后 | key 被删，下次查 miss | key 还在，但读出来发现"逻辑过期" |
| 响应 | miss → 必须查 DB → 阻塞 | hit → 返回旧值 + 后台重建 → **零阻塞** |

**为什么用逻辑过期防击穿？**

> 关键是**永远不让请求'扑空'**——逻辑过期下，缓存始终命中（即使是旧值），用户**零等待**。后台异步重建，重建期间所有请求都返回旧值，**只有一个线程查 DB**。

**代码引用**：`CacheClient.setWithLogicalExpire` line 48-56、`CacheClient.queryWithLogicalExpire` line 107-147

---

### Q5：你说的"DCL 双重检查"在缓存重建里起什么作用？

**问题**：抢到锁后，是不是直接重建？

```java
if (isLock) {
    // 直接重建？❌ 可能浪费
    CACHE_REBUILD_EXECUTOR.submit(() -> rebuild());
}
```

**潜在浪费**：

```
T1: A 抢到锁，submit 异步重建
T2: A 的异步任务完成（数据已刷新），unlock
T3: B 在 T1-T2 之间读了旧数据（expireTime 是旧值快照）
T4: B 抢到锁（A 刚释放）
T5: 没 DCL → B 又 submit 一次重建 ❌ DB 多查一次
    有 DCL → B 再读一次 Redis 发现已经新了 → 跳过重建 ✓
```

**DCL（Double-Checked Locking）作用**：抢到锁后**再读一次 Redis** 确认数据状态，避免重复重建。

**和单例 DCL 同源思想**：第一次无锁判断（快速通过），第二次有锁判断（防止重复操作）。

**加分点**：能讲出"Java 局部变量是快照、Redis 状态可能变化"的本质——和 volatile 的可见性问题异曲同工。

---

### Q6：缓存空值和布隆过滤器，你怎么选？

**对比**：

| 维度 | 缓存空值 | 布隆过滤器 |
|---|---|---|
| 内存 | 每个 null key 都占空间 | 1 亿 ID 仅 ~100MB |
| 速度 | 一次 Redis 网络往返 | 本地 O(1)，无网络 |
| 误判 | 无 | 可能误判（说有的可能没有）→ 退回查 Redis（无害） |
| 删除 | TTL 自动删 | 标准布隆不支持删（需计数布隆） |
| 复杂度 | 简单 | 引入新组件 |

**面试回答**：

> "教学项目用空值缓存简单实用。生产环境如果数据量大（百万级 ID），我会上 **RedisBloom 模块**或 **Redisson 自带的布隆过滤器**——拦在 Redis 之前，省内存且更快。代价是有微小误判率，但误判只会让请求'退回查 Redis'，无副作用。"

---

## 二、分布式锁

### Q7：为什么不能用 synchronized？为什么要用分布式锁？

**核心**：`synchronized` 是 **JVM 进程内**的锁——**集群部署下失效**。

**场景对比**：

```
单机部署：
  用户A 请求 → JVM-1 → synchronized 生效 ✓

集群部署（3 台服务器）：
  用户A 请求 → JVM-1 → 拿到 synchronized 锁
  用户B 请求 → JVM-2 → 也能拿到 JVM-2 的 synchronized 锁
  ↓
  两人都进了"临界区"，并发安全失效 ❌
```

**分布式锁**：把"互斥状态"放到**集群共享的存储**（Redis / Zookeeper / etcd）里，跨 JVM 也能互斥。

**项目选 Redis 分布式锁的理由**：
- 高性能（内存操作）
- 项目已经引入 Redis
- 配合 Redisson 库成熟稳定

---

### Q8：你自己实现过 Redis 分布式锁吗？遇到过哪些问题？

**项目里有 3 版演进**：

| 版本 | 实现 | 痛点 |
|---|---|---|
| V1 SimpleRedisLock | `SET NX EX` + 直接 DELETE | ❌ 误删别人的锁 |
| V2 SimpleRedisLock 改良 | 加 UUID + Lua 脚本释放 | ❌ 不可重入 + 不能续期 |
| V3 Redisson | Hash + Lua + 看门狗 + PubSub | ✅ 生产级 |

**5 大踩坑点**（按演进顺序）：

1. **误删锁**：A 锁 TTL 到期被自动释放、B 抢到锁，A 业务完调 `DEL` 删了 B 的锁 → **加 UUID + Lua 脚本校验**
2. **不可重入**：同一线程拿不了第二次锁 → **Hash 结构 + 重入计数**
3. **业务超时锁失效**：业务执行时间 > TTL → **看门狗自动续期**
4. **抢锁性能差**：自旋等待浪费 CPU → **PubSub 唤醒**
5. **判断和操作非原子**：先 GET 校验再 DEL → **整个释放逻辑用 Lua 脚本**

**代码引用**：`utils/SimpleRedisLock.java`（V2 版）、`service/impl/VoucherOrderServiceImpl.java`（V3 用 Redisson）

---

### Q9：误删锁怎么防？

**误删场景**：

```
T0: 线程A 拿到锁，TTL=10秒
T1: 线程A 业务卡顿（GC、慢SQL...）超过 10 秒
T2: 锁因 TTL 自动过期被释放
T3: 线程B 抢到锁，开始干活
T4: 线程A 终于干完了，调 unlock() → DELETE 锁 ❌ 把 B 的锁删了
T5: 线程C 又抢到锁 → 和 B 同时持锁 → 并发安全破坏
```

**防御方案（项目实现）**：

```java
// 加锁时：value = UUID + threadId
private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";

public boolean tryLock(long timeoutSec){
    long threadId = Thread.currentThread().getId();
    Boolean success = stringRedisTemplate.opsForValue()
            .setIfAbsent(KEY_PREFIX + name, ID_PREFIX + threadId, timeoutSec, TimeUnit.SECONDS);
    return Boolean.TRUE.equals(success);
}

// 释放时：Lua 脚本原子校验 + 删除
-- unlock.lua
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
```

**关键点**：
- value 用 **UUID（JVM 唯一）+ threadId（线程唯一）**，跨集群也不会撞
- 释放用 **Lua 脚本保证"判断+删除"原子**

**代码引用**：`utils/SimpleRedisLock.java`、`resources/unlock.lua`

---

### Q10：Redisson 锁的内部数据结构是什么？怎么实现可重入？

**数据结构 = Redis Hash**：

```
key = "lock:order:1001:5"

field 0:    "abc123:Thread-1"      ← UUID + 线程 ID
value 0:    1                        ← 重入计数

(重入第二次后)
field 0:    "abc123:Thread-1"
value 0:    2                        ← 计数 +1
```

**可重入实现**（简化版 Lua）：

```lua
if (redis.call('exists', KEYS[1]) == 0) then
    -- 锁不存在 → 创建并计数 1
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil
end

if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then
    -- 锁存在且 field 是自己 → 计数 +1
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil
end

-- 锁是别人的 → 返回剩余 TTL
return redis.call('pttl', KEYS[1])
```

**释放时**：计数 -1，到 0 才真删 key，同时 PubSub 唤醒等待者。

**加分点**：能说出"PubSub 唤醒避免空轮询"——比传统自旋等待性能高得多。

---

### Q11：看门狗（Watchdog）是什么？怎么工作？

**解决问题**：业务执行时间不可预测——TTL 设大了客户端崩了等很久才释放，设小了业务超时锁就没了。

**工作机制 3 句话**：

1. 加锁时默认 TTL = 30 秒
2. **后台 timer 线程每 10 秒检查一次**（TTL/3），还没释放就发 PEXPIRE 刷回 30 秒
3. **业务调 `unlock()` 或客户端宕机时**：看门狗停止 → TTL 不再续期 → 30 秒后自动释放

**关键陷阱**：

```java
// ❌ 看门狗不启用
lock.tryLock(1, 30, TimeUnit.SECONDS);   // leaseTime = 30 秒，不续期

// ✅ 看门狗启用
lock.tryLock(1, -1, TimeUnit.SECONDS);   // leaseTime = -1，启用看门狗
```

**死锁危险（必须背）**：

> "看门狗在独立线程跑——**业务代码抛异常 ≠ 看门狗停止**！必须 `try-finally + unlock()` 才能 cancel 掉看门狗的 timer task，否则锁无限续期 → 死锁。"

**代码引用**：`service/impl/VoucherOrderServiceImpl.java` line 67

---

### Q12：`tryLock(1L, -1, TimeUnit.SECONDS)` 三个参数分别什么意思？

```java
tryLock(long waitTime, long leaseTime, TimeUnit unit)
```

| 参数 | 含义 |
|---|---|
| `waitTime = 1L` | 等待获取锁的最大时间——抢不到就等 1 秒，期间抢到则返回 true |
| `leaseTime = -1` | **特殊值**：启用看门狗自动续期（业务跑多久锁就持多久） |
| `unit = SECONDS` | 时间单位，**同时作用于 waitTime 和 leaseTime** |

**为什么 leaseTime 用 -1？** 业务执行时间不可预测——交给看门狗自动管，比"硬编码 30 秒"安全。

**为什么 waitTime = 1？** 秒杀场景**用户最多等 1 秒**——再长用户就跑了。

**加分**：能区分 `lock.lock()` / `tryLock()` / `tryLock(wait, unit)` / `tryLock(wait, lease, unit)` 4 种重载。

---

## 三、秒杀架构

### Q13：超卖问题怎么解决？项目用什么方案？

**超卖根因**（极致重要）：

```
T1: 线程A 查 stock = 1
T2: 线程B 查 stock = 1
T3: A 判断 >= 1，通过
T4: B 判断 >= 1，通过
T5: A 扣减 → stock = 0
T6: B 扣减 → stock = -1 ❌
```

**根因**：'查 → 判 → 改'三步**非原子**，中间被其他线程插入。

**4 种解决方案**：

| 方案 | 评价 |
|---|---|
| synchronized | ❌ 单机才有效 |
| DB 悲观锁（SELECT FOR UPDATE） | 🟡 锁等待长 |
| **DB 乐观锁（CAS）** ⭐ | ✅ 项目选这个 |
| Redis 预扣 + 异步落库 | ✅✅ 真实秒杀终极方案 |

**项目实现（乐观锁）**：

```java
boolean success = seckillVoucherService.update()
        .setSql("stock = stock - 1")
        .eq("voucher_id", voucherId)
        .gt("stock", 0)               // ⭐ 关键
        .update();
```

**对应 SQL**：`UPDATE ... WHERE voucher_id = ? AND stock > 0`

**关键魔法**：InnoDB 行锁让 `WHERE 检查 + SET 修改`**原子执行**，不会被打断。

**代码引用**：`service/impl/VoucherOrderServiceImpl.java` line 132-139

---

### Q14：经典乐观锁要用版本号字段，黑马点评为什么不用？

**经典乐观锁**：

```sql
UPDATE table SET stock = stock - 1, version = version + 1
WHERE id = ? AND version = ?
```

需要额外字段 `version`，每次更新前先查询版本号。

**黑马点评简化版**：

```sql
UPDATE tb_seckill_voucher SET stock = stock - 1
WHERE voucher_id = ? AND stock > 0
```

**为什么能简化？** stock 本身就是**单调递减**的状态——"stock > 0"暗含了"还有库存可扣"的状态校验，**stock 兼任版本号的角色**。

**对比**：

| 方案 | 字段开销 | 适用 |
|---|---|---|
| 版本号 | +1 字段 | 通用（任何字段变化都能监测） |
| 库存条件 | 0 字段 | 特定场景（值单调变化） |

**加分**：能说出"两者等价性"——本质都是 CAS，只是判断条件不同。

---

### Q15：一人一单怎么实现？乐观锁能解决吗？

**核心认知**：**乐观锁防超卖，分布式锁防一人一单——这是两个不同的需求**。

| 维度 | 防超卖 | 防一人一单 |
|---|---|---|
| 约束的对象 | **数据本身**（stock） | **用户行为**（user） |
| 检查的内容 | "DB 还允许扣吗" | "这个用户已经下过单吗" |
| 用什么实现 | DB 乐观锁 | 分布式锁 / 唯一索引 |

**乐观锁防不住一人一单**：

```
用户 1001 发 100 个并发请求
每个请求都通过 stock > 0 检查（库存还有）
每个请求都扣 1
→ 用户 1001 抢到 100 张券 ❌
```

WHERE 条件**只看 stock**，**不知道是谁在扣**。

**项目实现（双保险）**：

1. **分布式锁**：`redissonClient.getLock("lock:order:" + userId + ":" + voucherId)` 保证同一用户同一券请求串行
2. **业务校验**：锁内 `query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0` 拦截重复下单

**生产推荐 + DB 唯一索引兜底**：

```sql
ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

**面试金句**：
> "我用分布式锁做前置串行化，真正的兜底是数据库唯一索引——**两层防御**。"

---

### Q16：锁粒度怎么设计？为什么是 `user + voucher`？

**3 种粒度对比**：

| 粒度 | key | 问题 |
|---|---|---|
| 全局锁 | `"lock:seckill:global"` | 所有人串行 → 性能崩 |
| 按用户 | `"lock:order:" + userId` | 用户同时抢 A、B 两张券也得排队（不必要） |
| **按用户+券** ⭐ | `"lock:order:" + userId + ":" + voucherId` | 完美匹配业务约束 |

**设计原则**：**锁粒度 = 业务唯一性约束**

业务约束："同一用户对同一券 1 单" → 锁就精确到这两个维度。

**通用迁移**：

| 业务约束 | 锁 key |
|---|---|
| 同一用户对同一券 1 单 | `lock:order:{userId}:{voucherId}` |
| 同一用户同时只能 1 笔交易 | `lock:trade:{userId}` |
| 同一房间同时 1 人编辑 | `lock:edit:{roomId}` |

---

## 四、Spring AOP & 事务

### Q17：为什么需要 `AopContext.currentProxy()`？this 调用有什么问题？

**根因**：**Spring 单例池里存的是代理对象，但类内 `this` 指向原始对象**。

**调用链路**：

```
Controller → 代理 0x5678 → 转发 → 原始对象 0x1234
                                       ↓
                              this 在原始对象方法里 = 0x1234（原始）
                                       ↓
                              this.createVoucherOrder() = 直接走原始
                              ⚠️ 绕过代理 → @Transactional 失效
```

**事故**：业务抛异常时不回滚 → 脏数据落库。

**解决**：

```java
IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
return proxy.createVoucherOrder(voucherId);
```

**原理**：
- `AopContext` 内部用 **ThreadLocal**
- Spring 在代理转发前**把代理对象塞 ThreadLocal**
- 原始对象方法内调 `currentProxy()` 拿出来

**启用条件**：启动类加 `@EnableAspectJAutoProxy(exposeProxy = true)`

**代码引用**：`service/impl/VoucherOrderServiceImpl.java` line 74

---

### Q18：`@Transactional` 失效有哪些场景？

**口诀：私 外 吞 非 栈**

| 字 | 失效场景 | 修复 |
|---|---|---|
| **私** | 方法**私**有（非 public） | 改 public |
| **外** | 抛**外**部异常（checked exception，如 SQLException）默认不回滚 | `rollbackFor = Exception.class` |
| **吞** | 异常被 catch **吞**掉 | 重抛 / `setRollbackOnly()` |
| **非** | **非** InnoDB 引擎（如 MyISAM） | 改 InnoDB |
| **栈** | **栈**内 this 调用（self-invocation） | `AopContext.currentProxy()` |

**加分场景（第 6 失效）**：传播行为错配——`REQUIRES_NEW` 时内层独立事务已提交，外层异常不会回滚内层。

**生产兜底**：`@Transactional(rollbackFor = Exception.class)` —— 几乎所有事务都该加。

**面试金句**：
> "项目里所有 @Transactional 方法都做了：① public ② 通过代理调用 ③ rollbackFor=Exception.class ④ 不主动 catch 异常 ⑤ 表都是 InnoDB。"

---

### Q19：为什么不直接给外层方法加 `@Transactional`？

**两个核心原因**：

#### 原因 1：DB 连接占用过长

```java
@Transactional
public Result seckillVoucher() {
    // 进入这刻就开事务，占一个 DB 连接
    lock.tryLock(1L, ...);    // 等锁 1 秒，事务空转占 DB 连接 ❌
    // 业务
    lock.unlock();
}                              // 退出才提交事务
```

高并发下 **DB 连接池被打爆**。

#### 原因 2（更关键）：锁释放和事务提交的时序错位

```java
@Transactional
public Result seckillVoucher() {
    lock.tryLock();
    try {
        // 修改 DB（写库存、写订单）
    } finally {
        lock.unlock();   // ⚠️ 释放锁时，事务还没提交
    }
    // 方法 return 后才提交
}
```

**事故时序**：

```
T1: 用户A 释放锁                 ← 事务未提交
T2: 用户B 抢到锁
T3: 用户B 查 count → 0（A 的订单还没提交，B 看不见）
T4: 用户B 通过校验、插入订单 → 一人多单 ❌
T5: 用户A 的事务才提交
```

**正确做法：锁包事务**

```java
public Result seckillVoucher() {       // 外层：无 @Transactional
    lock.tryLock();
    try {
        proxy.createVoucherOrder();    // 内层：有 @Transactional（事务在 unlock 前完成）
    } finally {
        lock.unlock();
    }
}
```

**面试金句**：
> "**锁包事务，不是事务包锁**——锁的范围必须大于事务，保证事务提交时锁还在，下个请求拿到锁时能看到最新 DB 状态。"

---

## 五、登录态与拦截器

### Q20：为什么用 Token + Redis，不用 Session？也不用 JWT？

**3 种方案对比**：

| 维度 | Session | JWT | UUID Token + Redis ⭐ |
|---|---|---|---|
| 跨节点 | ❌ 不能共享 | ✅ 自包含 | ✅ Redis 集中存储 |
| 服务端主动失效 | ✅ 删 Session | ❌ 难（需黑名单） | ✅ 删 Redis |
| 用户信息变更即时生效 | ✅ | ❌ 需重签发 | ✅ 改 Redis |
| 存储成本 | 每节点占内存 | 0（无状态） | Redis 占空间 |
| Token 大小 | 32 字符 | 几百字节 | 32 字符 |
| 安全性 | 中（CSRF 风险） | 高（签名） | 高（不可猜） |

**项目选 UUID + Redis 的核心理由**：**服务端可主动失效**——用户改密、被封号时直接删 Redis。

**配套设计**：
- Token = UUID（32 字符不带横杠），不携带任何业务信息
- Redis 数据结构 = Hash（field 是 UserDTO 字段，便于局部更新）
- 前端存 sessionStorage，请求头带 `authorization: <token>`

**代码引用**：`service/impl/UserServiceImpl.java` line 67-115

---

### Q21：Token 是随机 UUID 没用户信息，怎么识别用户？

**核心洞察**：**Token 不"携带"用户信息，它只是 Redis 里一条用户登录态记录的 key**。

**完整流程**：

```
登录成功：
  ① 生成随机 UUID = "abc123..."
  ② Redis 写入：login:token:abc123 → UserDTO Hash
  ③ 返回 token 给前端

后续请求：
  ① 前端 Header 带 token
  ② 拦截器拿 token 拼 key 查 Redis
  ③ 拿到 UserDTO，放 ThreadLocal
  ④ 业务代码 UserHolder.getUser() 拿到用户
```

**类比**：Token 像储物柜钥匙号——号码本身随机无意义，但**拿这把钥匙能开对应柜子取出你的东西**。

**为什么不直接传 userId？**

1. **可猜测**：攻击者改 `userId=1002` 就能冒充
2. **不能失效**：userId 永远是这个值，没法"踢下线"
3. **暴露业务**：userId=8 暴露你是早期用户、=1000000 暴露用户量

---

### Q22：双拦截器为什么是两个不是一个？

**根因**：**TTL 刷新需要每个请求都跑，但鉴权只需要部分路径**。

**单拦截器的痛点**：

```
LoginInterceptor 拦截 /order/** 等需登录路径
不拦截 /shop/**、/blog/hot 等公开路径

用户登录后浏览公开页面 30 分钟：
  → LoginInterceptor 不触发
  → Token TTL 没刷新
  → 用户点"我的订单" → 显示已过期 ❌

用户："我刚刚一直在用啊？"
```

**双拦截器分工**：

| 拦截器 | 路径 | 职责 |
|---|---|---|
| **RefreshTokenInterceptor** | `/**`（全部） | 拿 token 查 Redis → 放 ThreadLocal + 刷 TTL，**永远 return true** |
| **LoginInterceptor** | 受保护路径 | 看 ThreadLocal 是否有用户，没有 → 401 |

**顺序**：RefreshToken 先（order=0），Login 后（order=1）。

**单一职责原则**：
- RefreshToken：只负责加载用户 + 刷 TTL，**不依赖响应**
- Login：只负责鉴权，**不依赖 Redis**（依赖最小化）

**代码引用**：`utils/RefreshTokenInterceptor.java`、`utils/LoginInterceptor.java`、`config/MvcConfig.java`

---

### Q23：ThreadLocal 用完为什么必须 remove？

**Tomcat 用线程池复用线程**——如果不清理 ThreadLocal：

```
请求 A（用户 1001）：
  preHandle → 把 user 1001 存 ThreadLocal
  Controller 执行
  afterCompletion → 没 remove ❌
  线程归还线程池

请求 B（未登录用户，复用同一线程）：
  preHandle → token 为空，跳过
  Controller 调 UserHolder.getUser()
  → 拿到了用户 1001 的信息 😱
```

**两大事故**：
1. **水平越权**：用户 B 的请求看到用户 A 的信息
2. **内存泄漏**：ThreadLocalMap 的 Entry 持有 user 引用，GC 不掉

**正确做法**：在拦截器的 `afterCompletion` 调 `UserHolder.removeUser()`。

**为什么是 afterCompletion 不是 postHandle？**

→ Controller 抛异常时 `postHandle` 不执行，但 `afterCompletion` **一定执行**——保证清理。

**代码引用**：`utils/RefreshTokenInterceptor.java` line 43

---

## 六、横向知识点

### Q24：单例模式 DCL 必须配 volatile，为什么？

**根因**：`new Singleton()` 实际是 3 步，**JVM 可能重排序为 1→3→2**：

```
原始顺序：
  ① 分配内存
  ② 调用构造函数初始化
  ③ 把引用赋给 instance

重排序后：
  ① 分配内存
  ③ instance = 已分配的地址（但对象还没初始化！）
  ② 调用构造函数
```

**问题**：

```
Thread A 执行到 ③：instance != null 了
Thread B 此时来：
  if (instance == null) → false
  return instance      ← 拿到"半成品"
  访问 instance.someField → NPE 或默认值
Thread A 才执行 ②：初始化
```

**volatile 的两大作用**：

| 作用 | 说明 |
|---|---|
| 禁止指令重排序 | 保证 1→2→3 严格顺序 |
| 保证可见性 | 一个线程修改后，其他线程立即可见（不会缓存到 CPU 寄存器） |

**完整代码**：

```java
private static volatile Singleton instance;   // ⭐ volatile

public static Singleton getInstance() {
    if (instance == null) {                       // 外层无锁判断
        synchronized (Singleton.class) {
            if (instance == null) {               // 内层有锁判断
                instance = new Singleton();
            }
        }
    }
    return instance;
}
```

**加分点**：
- 推荐用静态内部类 / 枚举替代 DCL（更简洁）
- 《Effective Java》推荐 enum 单例（防反射 + 防序列化破坏）

---

### Q25：MyBatis-Plus 链式调用是怎么实现的？

**4 步公式**：

```java
service.update()                  // ① 起手：返回 wrapper 对象
    .setSql("stock = stock - 1")  // ② 配置：返回 this（自己）
    .eq("voucher_id", voucherId)  // ② 配置：返回 this
    .gt("stock", 0)                // ② 配置：返回 this
    .update();                     // ③ 终止：发 SQL 给 MySQL
```

**实现原理**：

1. **Builder / Fluent Interface 模式**：每个配置方法返回 `this`（wrapper 自身），所以能链式串
2. **wrapper 内部累积条件**：`.eq()` 时往一个 List 里加 `("voucher_id", "=", voucherId)` 三元组
3. **终止方法解析**：`.update()` 时遍历 wrapper 的条件 List，**动态拼 SQL**，发给 MyBatis 执行

**典型起手 / 配置 / 终止方法**：

| 类型 | 例子 |
|---|---|
| 起手 | `query()` / `update()` / `lambdaQuery()` / `lambdaUpdate()` |
| 配置 | `eq` / `gt` / `ge` / `lt` / `le` / `like` / `in` / `setSql` |
| 终止（查询） | `.one()` / `.list()` / `.count()` / `.page()` |
| 终止（更新） | `.update()` / `.remove()` |

**Lambda 版优势**：编译期类型检查（字段重命名自动跟着改）。

**同类设计模式**：Spring `RestTemplate`、Java 8 Stream API、StringBuilder——都是流式接口。

**代码引用**：参考 `service/impl/VoucherOrderServiceImpl.java` line 126、132、`UserServiceImpl.java` line 84

---

## 📋 面试前自检清单

### 必须能不假思索回答的"硬核题"

- [ ] Q1：缓存穿透三态判断（null / "" / 真实数据）
- [ ] Q2：逻辑过期 + DCL 双重检查
- [ ] Q9：Lua 脚本 + UUID 防误删
- [ ] Q10：Redisson Hash 数据结构 + 可重入实现
- [ ] Q11：看门狗 = HashedWheelTimer + Lua 续期，业务异常不停看门狗
- [ ] Q13：超卖根因 = 三步非原子；解决 = WHERE 条件 + UPDATE 原子
- [ ] Q15：乐观锁防超卖 ≠ 分布式锁防一人一单
- [ ] Q17：this 调用绕过代理 → @Transactional 失效 → AopContext 救场
- [ ] Q18：5 大失效场景"私外吞非栈"口诀
- [ ] Q19：锁包事务 vs 事务包锁
- [ ] Q20：Token + Redis vs JWT vs Session 三方案对比
- [ ] Q22：双拦截器职责分离（RefreshToken vs Login）
- [ ] Q23：ThreadLocal 必须 remove，否则 Tomcat 线程池复用导致越权
- [ ] Q24：DCL 必须配 volatile（指令重排序）

### 加分点速查

| 主题 | 加分关键词 |
|---|---|
| 缓存 | 布隆过滤器 / Caffeine 二级缓存 / RedisBloom |
| 分布式锁 | PubSub 唤醒 / HashedWheelTimer / Redlock |
| 秒杀 | Redis Stream 异步队列 / 唯一索引兜底 |
| AOP | CGLib vs JDK 代理 / `setRollbackOnly()` |
| 登录态 | HttpOnly + Secure + SameSite Cookie / 双 token 兜底 |
| 单例 | enum 单例 / 静态内部类 / 防反射 |

### 演进故事金句

> "我做过 3 版迭代：V1 无锁版超卖 → V2 SimpleRedisLock 不可重入 → V3 Redisson 一站式。"
> "我用分布式锁前置 + DB 唯一索引兜底——两层防御。"
> "锁包事务，不是事务包锁——锁的范围必须大于事务。"
> "Token 不携带信息，只是 Redis 里一条记录的 key——服务端可主动失效。"
> "看门狗在独立线程，业务异常不停它——所以必须 try-finally + unlock。"

---

> 题库完。配套阅读 `NOTE_单例模式与Spring单例管理.md` 加深原理。
> 面试当天：临场前扫一遍"必须能不假思索回答"和"加分点速查"。
