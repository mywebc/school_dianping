package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        // 3. 判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // userId.toString().intern() 这样是为了保证锁的唯一性（保证当用户的id一样, 锁就一样）
        synchronized (userId.toString().intern()) {
            // 获取代理对象， 通过代理对象调用createVoucherOrder方法， 这样就会走事务， 保证事务的一致性
            // 为了获取成功代理对象， pom要引入aspectjweaver, 而且启动类要加上@EnableAspectJAutoProxy(exposeProxy = true)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);

            //return this.createVoucherOrder(voucherId)
            // 上面这么写this指的是当前对象， 事务要想生效必须代理对象调用， 不能直接调用， 代理对象调用会走事务
            // 但是这里this指的是当前对象， 代理对象是在spring容器中的， 所以这里直接调用不会走事务
            // 所以这里要获取代理对象， 通过代理对象调用createVoucherOrder方法， 这样就会走事务， 保证事务的一致性
        }

        // 这里这个锁为什么要加在函数外面？，为了保证函数执行完， 事务已经提交了，才释放锁，
        // 如果加在函数里面， 事务还没提交，锁就释放了， 这样就会出现问题（其他线程可能就会进来）
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5. 一人一单的情况， 判断用户是否已经秒杀过
        // 查询订单
        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判读用户是否存在
        if (count > 0) {
            // 如果存在， 说明用户已经秒杀过
            return Result.fail("您已经秒杀过了");
        }

        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        // 6. 生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id 这个我们可以用我们写的redis的id生成器
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
