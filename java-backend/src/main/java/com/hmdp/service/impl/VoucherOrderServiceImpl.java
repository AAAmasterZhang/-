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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
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

    public Result secKillVoucher(Long voucherId){
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("优惠券秒杀还未开始");
        }

        //判断是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("优惠券秒杀已结束");
        }

        //判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("优惠券库存不足");
        }

        return createVoucherOrder(voucherId);

    }

    @Resource
    private RedissonClient redissonClient;

    /**
     * 创建订单，实现一人一单功能，包含分布式锁
     * @param voucherId
     * @return
     */
    @Transactional  //
    public Result createVoucherOrder(Long voucherId){
        Long userId = UserHolder.getUser().getId();

        //创建锁对象 这里的实例只是一个本地的类对象，不会操作到redis
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);

        //尝试获取锁 这一步才是操作redis
        boolean isLock = redisLock.tryLock();
        if(!isLock){
            return Result.fail("您已下过单，请稍后再试");
        }

        try{
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //判断是否存在
        if(count > 0){
            return Result.fail("您已下过单，请稍后再试");
        }

        //不存在，扣减库存
        //乐观锁，同一时间只有一个update能执行--所以假设不会并发冲突，只在修改数据库的时候判断是否stock大于0
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) //乐观锁，判断库存是否大于0
                .update();
        if(!success){
            return Result.fail("优惠券库存不足");
        }

        //创建订单，订单id用户id优惠券id
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //返回订单id
        return Result.ok(orderId);
        }finally {
            //释放锁
            redisLock.unlock();
        }

    }





/*    @Transactional
    public Result secKillVoucher(Long voucherId){
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断是否是秒杀时间，开始和结束判断
        if(voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("优惠券不是秒杀时间");
        }

        //判断是否有库存
        if(voucher.getStock() < 1){
            return Result.fail("优惠券库存不足");
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) //乐观锁，判断库存是否大于0
                .update();
        if(!success){
            return Result.fail("优惠券库存不足");
        }

        //创建订单 写入订单id 用户id 优惠券id
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        Long UserId = UserHolder.getUser().getId();
        voucherOrder.setUserId(UserId);

        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //返回订单id
        return Result.ok(orderId);


    }*/


}
