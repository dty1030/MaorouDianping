package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *

 */
@Service
public class VoucherServiceImplMQ extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("MQ.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

    }
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        //保存秒杀库存到Redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());


    }


    public static final String QUEUE_NAME = "stream.orders";


    //创建一个线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        public void run(){
            while (true){
                try {
                    List<MapRecord<String , Object, Object>> Remains= stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                    );
                    if (Remains != null && !Remains.isEmpty()){
                        //3. 解析消息中的订单消息
                        MapRecord<String, Object, Object> record = Remains.get(0);
                        //获取数据: {userId, voucherId, id}
                        Map<Object, Object> values = record.getValue();
                        VoucherOrder voucherOrder = new VoucherOrder();
                        voucherOrder.setUserId(Long.valueOf((values.get("userId").toString())));
                        voucherOrder.setVoucherId((Long.valueOf(values.get("voucherId").toString())));
                        voucherOrder.setId(Long.valueOf(values.get("id").toString()));
                        //3. 如果获取成功， 可以下单

                        proxy.handleVoucherOrder(voucherOrder);
                        //4. ACK确认
                        stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());
                        continue;

                    }
                    //1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String , Object, Object>> list= stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    //2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //如果获取失败, 说明没消息, 继续下一次循环
                        continue;
                    }
                    //3. 解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    //获取数据: {userId, voucherId, id}
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setUserId(Long.valueOf((values.get("userId").toString())));
                    voucherOrder.setVoucherId((Long.valueOf(values.get("voucherId").toString())));
                    voucherOrder.setId(Long.valueOf(values.get("id").toString()));
                    //3. 如果获取成功， 可以下单

                    proxy.handleVoucherOrder(voucherOrder);
                    //4. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());


                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }

        }
    }

    private IVoucherService proxy;

    public Result seckillVoucher(Long voucherId){

        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单
        long orderId = redisIdWorker.nextId("order");
        //执行脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //判断结果是否为 0
        int r = result.intValue();
        if (r != 0)return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        //如果为0， 则说明校验通过
        //获取代理对象
        proxy = (IVoucherService) AopContext.currentProxy();
        return Result.ok(orderId);
    }


    @Transactional
    public void handleVoucherOrder(VoucherOrder voucherOrder){

        seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        voucherOrderService.save(voucherOrder);
    }


}
