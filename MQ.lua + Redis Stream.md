好的！帮你整理成完整的Markdown格式：

```markdown
## MQ.lua + Redis Stream 版

### 🎯 背景：解决 BlockingQueue 的两个缺陷

**上一个方案的问题：**

1. ❌ 队列在 JVM 内存，服务重启数据丢失
2. ❌ 队列容量有限，内存撑不住

**解决思路：**
- 把队列从 JVM 内存移到 **Redis Stream**

> 💡 **Redis Stream** 是 Redis 5.0 引入的消息队列数据结构，数据持久化在 Redis，服务重启不丢失，还支持消费者组、ACK 确认机制。

---

### 📜 MQ.lua：比 seckill.lua 多了一步

```lua
-- MQ.lua

local voucherId = ARGV[1]
local userId    = ARGV[2]
local orderId   = ARGV[3]   -- 多了订单ID参数

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1. 查是否重复下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 2. 查库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 3. 扣库存 + 记录用户 + 发消息到 Stream（三步原子）
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.orders', '*',    -- * 表示自动生成消息ID
    'userId', userId,
    'voucherId', voucherId,
    'id', orderId)

return 0
```

**和 seckill.lua 的核心区别：**
- 第三步多了 `xadd`，直接在 Lua 里把消息写入 Stream
- 扣库存、记录用户、发消息，**三步一起原子完成**

---

### 💻 Java 调用端

```java
// VoucherServiceImplMQ.java

public Result seckillVoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");  // 提前生成订单ID，传给Lua
    
    Long result = stringRedisTemplate.execute(
        SECKILL_SCRIPT,
        Collections.emptyList(),
        voucherId.toString(),   // ARGV[1]
        userId.toString(),      // ARGV[2]
        String.valueOf(orderId) // ARGV[3] ← 比上一版多传了这个
    );
    
    if (result != 0) {
        return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
    }
    
    proxy = (IVoucherService) AopContext.currentProxy();
    return Result.ok(orderId);  // 直接返回，不需要自己维护队列了
}
```

**关键变化：**
- 提前生成 `orderId`，传给 Lua 脚本
- 不需要手动 `add` 到 `BlockingQueue`
- Lua 脚本内部直接写入 Stream

---

### 🔄 消费者线程：先处理 pending list，再处理新消息

**这是这个版本最关键的设计，流程分两段：**

```java
private class VoucherOrderHandler implements Runnable {
    @Override
    public void run() {
        while (true) {
            try {
                // ① 先读 pending list（已投递但未 ACK 的消息）
                List<MapRecord<String, Object, Object>> remains = 
                    stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))  // 从头读未ACK的
                    );
                
                if (remains != null && !remains.isEmpty()) {
                    // 处理 pending list 里的消息
                    handleAndAck(remains.get(0));
                    continue;  // 处理完继续检查 pending list
                }
                
                // ② pending list 空了，再读新消息
                List<MapRecord<String, Object, Object>> list = 
                    stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())  // 读新的
                    );
                
                if (list == null || list.isEmpty()) continue;
                
                handleAndAck(list.get(0));
                
            } catch (Exception e) {
                log.error("处理订单异常", e);
            }
        }
    }
}
```

**处理和确认：**

```java
private void handleAndAck(MapRecord<String, Object, Object> record) {
    // 1. 解析消息
    Map<Object, Object> value = record.getValue();
    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(
        value, new VoucherOrder(), true
    );
    
    // 2. 创建订单
    handleVoucherOrder(voucherOrder);
    
    // 3. ACK 确认
    stringRedisTemplate.opsForStream().acknowledge(
        QUEUE_NAME, "g1", record.getId()
    );
}
```

---

### 🤔 为什么要先读 pending list？

#### 正常流程：

```
消息投递 → 消费者读取 → 处理成功 → ACK → 消息从 pending list 移除
```

#### 异常流程：

```
消息投递 → 消费者读取 → 处理中服务崩溃 → 没有 ACK
           ↓
    消息留在 pending list
           ↓
    服务重启后，先读 pending list，把上次没处理完的补上
```

> 💡 **这就是 Redis Stream 相比 BlockingQueue 的核心优势：消息不丢失，崩溃可恢复。**

---

### 📊 两个版本完整对比

| 对比项 | BlockingQueue 版 | Redis Stream 版 |
|--------|-----------------|----------------|
| **队列位置** | JVM 内存 | Redis |
| **服务重启** | ❌ 数据丢失 | ✅ 数据保留 |
| **消息确认** | ❌ 无 | ✅ ACK 机制 |
| **崩溃恢复** | ❌ 无 | ✅ pending list |
| **Lua 参数** | voucherId, userId | voucherId, userId, orderId |
| **Lua 第三步** | 扣库存 + sadd | 扣库存 + sadd + xadd |

---

### 💬 面试常见追问

#### Q1：ReadOffset.from("0") 和 ReadOffset.lastConsumed() 区别？

**A：**

| ReadOffset | 对应符号 | 作用 |
|-----------|---------|------|
| **from("0")** | `0` | 从头读起，拿到所有未 ACK 的历史消息（pending list） |
| **lastConsumed()** | `>` | 只读当前消费者组还没消费过的新消息 |

**示例：**

```redis
# 读 pending list
XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0

# 读新消息
XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
```

---

#### Q2：ACK 之前服务又崩了怎么办？

**A：**

**场景：**
```
消息投递 → 消费者读取 → 开始处理 → 崩溃（未 ACK）
```

**恢复：**
- 消息还在 **pending list**
- 下次重启继续从 `"0"` 读，会再次处理

**幂等性保证：**
```java
// handleVoucherOrder 里的数据库操作要能幂等（重复执行结果一样）

// 方式1：乐观锁防止超卖
boolean success = seckillVoucherService.update()
    .setSql("stock = stock - 1")
    .eq("voucher_id", voucherId)
    .gt("stock", 0)  // 库存>0 才扣减
    .update();

// 方式2：唯一索引防重复插入
// ALTER TABLE voucher_order 
// ADD UNIQUE INDEX uk_user_voucher (user_id, voucher_id);
```

---

#### Q3：为什么订单ID在 Java 里生成而不是数据库自增？

**A：**

**时间线：**

```
Java 生成 orderId
    ↓
Lua 脚本执行（写入 Stream，此时还没写数据库）
    ↓
消息在 Stream 里（包含 orderId）
    ↓
消费者线程从 Stream 读取（拿到 orderId）
    ↓
写入数据库（使用这个 orderId）
```

**为什么不能用数据库自增：**
- Lua 脚本执行时，**还没写数据库**
- 数据库自增 ID 此时**还不存在**
- 必须提前生成全局唯一 ID

**解决方案：**
```java
// RedisIdWorker 生成全局唯一 ID
long orderId = redisIdWorker.nextId("order");
```

---

### 🎯 五个锁/方案全部回顾

| 序号 | 方案 | 解决问题 | 状态 |
|------|------|---------|------|
| **1** | 互斥锁 SET NX | 缓存击穿，控制重建线程 | 🟢 激活 |
| **2** | SimpleRedisLock | 分布式一人一单 | 💤 注释（被Redisson替代） |
| **3** | Redisson RLock | 可重入 + 续期 + 原子释放 | 🟢 激活 |
| **4** | seckill.lua + BlockingQueue | 异步下单，Lua替代锁 | 🟡 激活（被MQ版覆盖） |
| **5** | MQ.lua + Redis Stream | 消息持久化，崩溃恢复 | 🟢 激活 |

**演进路径：**

```
同步锁方案（性能差）
    ↓
Redisson（解决锁问题）
    ↓
Lua + BlockingQueue（异步提速）
    ↓
Lua + Redis Stream（数据可靠性）
```

---

### 🏆 最终方案特性总结

**核心优势：**

```
1. 高性能
   → Lua 脚本原子操作，无需加锁
   → 异步处理，用户请求秒级返回

2. 高可用
   → 消息持久化在 Redis
   → 服务重启不丢失

3. 高可靠
   → ACK 确认机制
   → pending list 崩溃恢复
   → 幂等性保证

4. 高并发
   → 消费者组支持多实例
   → 水平扩展能力
```

**技术栈：**
- Redis Stream（消息队列）
- Lua 脚本（原子操作）
- ACK 机制（消息确认）
- Pending List（崩溃恢复）
- 幂等性设计（数据一致性）
```

---

## 额外补充（可选）

如果想让笔记更完整，可以再加上：

```markdown
### 🔧 完整代码示例

#### 1. 初始化消费者组

```java
@PostConstruct
private void init() {
    // 创建消费者组（项目启动时执行一次）
    try {
        stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, "g1");
    } catch (Exception e) {
        // 组已存在，忽略
    }
    
    // 启动消费者线程
    SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
}
```

---

#### 2. 完整的消费者实现

```java
private class VoucherOrderHandler implements Runnable {
    @Override
    public void run() {
        while (true) {
            try {
                // 1. 先处理 pending list
                List<MapRecord<String, Object, Object>> pendingList = 
                    stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                    );
                
                if (pendingList != null && !pendingList.isEmpty()) {
                    MapRecord<String, Object, Object> record = pendingList.get(0);
                    handleAndAck(record);
                    continue;
                }
                
                // 2. pending list 空了，读新消息
                List<MapRecord<String, Object, Object>> newList = 
                    stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                
                if (newList == null || newList.isEmpty()) {
                    continue;
                }
                
                MapRecord<String, Object, Object> record = newList.get(0);
                handleAndAck(record);
                
            } catch (Exception e) {
                log.error("处理订单异常", e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }
    
    private void handleAndAck(MapRecord<String, Object, Object> record) {
        try {
            // 1. 解析消息
            Map<Object, Object> value = record.getValue();
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(
                value, new VoucherOrder(), true
            );
            
            // 2. 处理订单
            handleVoucherOrder(voucherOrder);
            
            // 3. ACK 确认
            stringRedisTemplate.opsForStream().acknowledge(
                QUEUE_NAME, "g1", record.getId()
            );
        } catch (Exception e) {
            log.error("处理订单失败", e);
            // 不 ACK，消息留在 pending list
        }
    }
}
```

---

### 📈 Redis Stream 命令速查

```redis
# 创建消费者组
XGROUP CREATE stream.orders g1 0 MKSTREAM

# 发送消息
XADD stream.orders * userId 1001 voucherId 5 id 123456

# 读新消息
XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >

# 读 pending list
XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0

# ACK 确认
XACK stream.orders g1 1234567890123-0

# 查看 pending list
XPENDING stream.orders g1

# 查看消费者组信息
XINFO GROUPS stream.orders

# 查看消费者信息
XINFO CONSUMERS stream.orders g1
```

---

### 🐛 常见问题排查

#### 问题1：消息重复消费

**现象：**
- 同一个订单被处理多次
- 数据库出现重复数据

**原因：**
- ACK 之前服务崩溃
- 消息在 pending list 中被重新消费

**解决：**
```java
// 方式1：数据库唯一索引
ALTER TABLE voucher_order 
ADD UNIQUE INDEX uk_user_voucher (user_id, voucher_id);

// 方式2：Redis 去重
String key = "order:created:" + userId + ":" + voucherId;
Boolean success = stringRedisTemplate.opsForValue()
    .setIfAbsent(key, "1", 1, TimeUnit.DAYS);
if (!success) {
    return; // 已处理过
}
```

---

#### 问题2：pending list 堆积

**现象：**
- pending list 消息越来越多
- 消费速度跟不上生产速度

**排查：**
```redis
# 查看 pending list
XPENDING stream.orders g1

# 结果示例
1) (integer) 1000  # 堆积了 1000 条
2) "1234567890123-0"  # 最早的消息ID
3) "1234567890999-0"  # 最新的消息ID
```

**解决：**
```java
// 1. 增加消费者实例
SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler()); // 多开几个

// 2. 批量处理
StreamReadOptions.empty().count(10) // 一次读10条

// 3. 优化业务逻辑
// - 减少数据库查询
// - 使用批量插入
// - 添加索引
```
```

---

这样整理完整吗？需要我帮你把所有 Redis 相关的笔记（缓存穿透/击穿/雪崩、SimpleRedisLock、Redisson、Lua 方案等）整合成一个完整的 `Redis.md` 文件吗？