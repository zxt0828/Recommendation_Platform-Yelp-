package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import java.time.LocalDateTime;
import javax.annotation.Resource;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
  @Resource
  private ISeckillVoucherService seckillVoucherService;

  @Resource
  private RedisIdWorker redisIdWorker;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Override
  public Result seckillVoucher(Long voucherId) {
    // 1. 查询优惠券信息
    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

    // 2. 判断秒杀是否开始
    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
      return Result.fail("秒杀尚未开始！");
    }

    // 3. 判断秒杀是否结束
    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
      return Result.fail("秒杀已经结束！");
    }

    // 4. 判断库存是否充足
    if (voucher.getStock() < 1) {
      return Result.fail("库存不足！");
    }

    Long userId = UserHolder.getUser().getId();
    SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    boolean isLock = lock.tryLock(1200L);
// 判断是否获取锁成功
    if (!isLock) {
      return Result.fail("不允许重复下单");
    }
    try {
      IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
      return proxy.createVoucherOrder(voucherId);
    } finally {
      // 释放锁
      lock.unlock();
    }
  }

  @Transactional
  public Result createVoucherOrder(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    if(count > 0){
      return Result.fail("用户已达购买上限，限购一单");
    }

    // 5. 扣减库存
    boolean success = seckillVoucherService.update()
        .setSql("stock = stock - 1")
        .eq("voucher_id", voucherId)
        .gt("stock", 0)
        .update();
    if (!success) {
      return Result.fail("库存不足！");
    }

    // 6. 创建订单
    VoucherOrder voucherOrder = new VoucherOrder();
    // 6.1 订单ID
    long orderId = redisIdWorker.nextId("order");
    voucherOrder.setId(orderId);
    // 6.2 用户ID
    voucherOrder.setUserId(userId);
    // 6.3 优惠券ID
    voucherOrder.setVoucherId(voucherId);
    save(voucherOrder);

    // 7. 返回订单ID
    return Result.ok(orderId);
  }
}

