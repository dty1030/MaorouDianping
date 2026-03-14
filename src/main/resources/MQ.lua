local voucherId = ARGV[1]

local userId = ARGV[2]

local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId

local orderKey = 'seckill:order:' .. voucherId

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2 --2代表重复下单
end
--脚本业务
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end



--扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
--下单(保存用户) sadd orderKey userId
redis.call('sadd', orderKey, userId)
--发送消息到队列中, XADD stream.orders = k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*',
'userId', userId,
'voucherId', voucherId,
'id', orderId)