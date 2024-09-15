package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
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
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    // 静态加载
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 指定位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 指定类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXCUTOR = Executors.newSingleThreadExecutor();

    // 这是spring 提供的注解，加载类的时候就执行这个方法
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 从消息列中获取订单 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息是否获取成功
                    if (list == null | list.isEmpty()) {
                        // 获取失败， 继续下一次循环
                        continue;
                    }
                    // 取消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 获取成功，生成订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("生成订单失败", e);
                    handlePeddingList();
                }
            }
        }
        private void handlePeddingList() {
            while (true) {
                try {
                    // 从peddingList获取订单 XREADGROUP GROUP g1 c1 COUNT 1  STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息是否获取成功
                    if (list == null | list.isEmpty()) {
                        // pedding-list 没有消息， 就结束循环
                        break;
                    }
                    // 取消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 获取成功，生成订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("pedding-list异常", e);
                }
            }
        }
    }


//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    // 线程任务
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 从阻塞队列中获取订单
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 生成订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("生成订单失败", e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 使用redisson获取锁
        RLock lock = redissonClient.getLock("order" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock();

        if (!isLock) {
            log.error("一人只抢一单，请勿重复抢购");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 订单id 这个我们可以用我们写的redis的id生成器
        long orderId = redisIdWorker.nextId("order");
        // 1 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2. 判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 3. 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 5. 返回订单id
        return Result.ok(0);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 获取用户id
//        Long userId = UserHolder.getUser().getId();
//
//        // 1 执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        // 2. 判断结果是否为0
//        int r = result.intValue();
//        if (r != 0) {
//            // 3. 不为0，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 4. 为0，代表有购买资格，把下单的信息保存到阻塞的队列中， 后续可以根据这个队列进行下单
//        //  TODO 保存到阻塞队列
//        // 4. 生成订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 订单id 这个我们可以用我们写的redis的id生成器
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 用户id
//        voucherOrder.setUserId(userId);
//        // 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 保存到阻塞队列
//        orderTasks.add(voucherOrder);
//
//        // 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        // 5. 返回订单id
//        return Result.ok(0);
//    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀未开始");
//        }
//        // 3. 判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//        // 4. 判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // userId.toString().intern() 这样是为了保证锁的唯一性（保证当用户的id一样, 锁就一样）
////        synchronized (userId.toString().intern()) {
////            // 获取代理对象， 通过代理对象调用createVoucherOrder方法， 这样就会走事务， 保证事务的一致性
////            // 为了获取成功代理对象， pom要引入aspectjweaver, 而且启动类要加上@EnableAspectJAutoProxy(exposeProxy = true)
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////
////            //return this.createVoucherOrder(voucherId)
////            // 上面这么写this指的是当前对象， 事务要想生效必须代理对象调用， 不能直接调用， 代理对象调用会走事务
////            // 但是这里this指的是当前对象， 代理对象是在spring容器中的， 所以这里直接调用不会走事务
////            // 所以这里要获取代理对象， 通过代理对象调用createVoucherOrder方法， 这样就会走事务， 保证事务的一致性
////        }
//
//        // 这里这个锁为什么要加在函数外面？，为了保证函数执行完， 事务已经提交了，才释放锁，
//        // 如果加在函数里面， 事务还没提交，锁就释放了， 这样就会出现问题（其他线程可能就会进来）
//
//        // 下面是手动实现锁的版本
//        // 创建锁的对象
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//
//        // 使用redisson获取锁
//        RLock lock = redissonClient.getLock("order" + userId);
//
//        // 尝试获取锁
////        boolean isLock = simpleRedisLock.tryLock(1200);
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            return Result.fail("一人只抢一单，请勿重复抢购");
//        }
//        try {
//            // 获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // 释放锁
////            lock.unLock();
//            lock.unlock();
//        }
//    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5. 一人一单的情况， 判断用户是否已经秒杀过
        // 查询订单
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        // 判读用户是否存在
        if (count > 0) {
            // 如果存在， 说明用户已经秒杀过
            log.error("您已经秒杀过了");
            return;
        }

        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        // 6. 生成订单
        save(voucherOrder);
    }


//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5. 一人一单的情况， 判断用户是否已经秒杀过
//        // 查询订单
//        Long userId = UserHolder.getUser().getId();
//
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        // 判读用户是否存在
//        if (count > 0) {
//            // 如果存在， 说明用户已经秒杀过
//            return Result.fail("您已经秒杀过了");
//        }
//
//        // 5. 扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1") // set stock = stock - 1
//                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
//                .update();
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        // 6. 生成订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 订单id 这个我们可以用我们写的redis的id生成器
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 用户id
//        voucherOrder.setUserId(userId);
//        // 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        return Result.ok(orderId);
//    }
}
