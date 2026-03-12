
--查库存
local stock = redis.call('get', KEYS[1])
if (tonumber(stock) <= 0) then
    return 1
end
--查当前用户是否在已购用户集合中
local hasOrder = redis.call('sismember', KEYS[2], ARGV[1])
if(hasOrder == 1) then
    return 2
end

redis.call('incrby', KEYS[1], -1)
redis.call('sadd', KEYS[2], ARGV[1])
return 0

--  第三步：两个条件都通过
--          → 扣减库存（incrby -1）
--          → 把用户 ID 加入集合（sadd）
redis.call('sadd')