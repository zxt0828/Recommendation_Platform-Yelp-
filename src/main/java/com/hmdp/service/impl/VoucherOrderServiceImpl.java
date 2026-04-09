package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

  @Resource
  private RedissonClient redissonClient;

  private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
  static {
    SECKILL_SCRIPT = new DefaultRedisScript<>();
    SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
    SECKILL_SCRIPT.setResultType(Long.class);
  }

  private IVoucherOrderService proxy;
  //创建一个阻塞队列
  //private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
  //创建一个后台线程池
  private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

  //确保后台线程在程序启动的时候就开启，而不是有订单才开始，使用postconstruct注解的作用
  @PostConstruct
  public void init(){
    //给后台线程发任务
    SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
  }

  //交给后台线程的任务
  private class VoucherOrderHandler implements Runnable{
  //blocking queue
  //    @Override
  //    public void run(){
  //      while(true){
  //        try{
  //          //这里的take方法有自动休眠的底层功能
  //          VoucherOrder voucherOrder = orderTasks.take();
  //          handleVoucherOrder(voucherOrder);
  //        } catch (Exception e) {
  //          log.error("处理订单异常", e);
  //        }
  //      }
  //    }
    String queueName = "stream.orders";
    @Override
    public void run(){
      while(true){
        try{
          //从消费者组读取下一个未读的消息
          List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
              Consumer.from("g1", "c1"),
              StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
              StreamOffset.create(queueName, ReadOffset.lastConsumed())
          );
          //检查消息是否获取成功
          if (list == null || list.isEmpty()) {
            continue;
          }
          //解析消息
          MapRecord<String, Object, Object> record = list.get(0);
          Map<Object, Object> values = record.getValue();
          VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
          //处理订单
          handleVoucherOrder(voucherOrder);
          //XACK确认消息
          stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
        }catch (Exception e){
          log.error("订单处理异常", e);
          handlePendingList();
        }
      }
    }

    private void handlePendingList(){
      while(true){
        try{
          // 从 pending-list 中读取未确认的消息
          List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
              Consumer.from("g1", "c1"),
              StreamReadOptions.empty().count(1),
              StreamOffset.create(queueName, ReadOffset.from("0"))
          );
          // 没有未确认消息，结束循环
          if (list == null || list.isEmpty()) {
            break;
          }
          // 解析并处理
          MapRecord<String, Object, Object> record = list.get(0);
          Map<Object, Object> values = record.getValue();
          VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
          handleVoucherOrder(voucherOrder);
          // XACK 确认
          stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
        } catch (Exception e) {
          log.error("pending-list处理异常", e);
          try {
            Thread.sleep(20);
          } catch (InterruptedException ex) {
            ex.printStackTrace();
          }
        }
      }
    }
  }

  public void handleVoucherOrder(VoucherOrder voucherOrder){
    //获取用户id
    Long userId = voucherOrder.getUserId();
    //创建锁
    RLock lock = redissonClient.getLock("lock:order:" + userId);
    //获取锁
    boolean isLock = lock.tryLock();
    //判断是否获取锁成功
    if(!isLock){
      log.error("不允许重复下单");
      return;
    }
    try{
      proxy.createVoucherOrder(voucherOrder);
    }finally{
      lock.unlock();
    }
  }

  @Override
  public Result seckillVoucher(Long voucherId) {
    // 1. 为0，有购买资格，生成订单id
    long orderId = redisIdWorker.nextId("order");

    // 2. 执行Lua脚本
    Long result = stringRedisTemplate.execute(
        SECKILL_SCRIPT,
        Collections.emptyList(),
        voucherId.toString(),
        UserHolder.getUser().getId().toString(),
        String.valueOf(orderId)
    );

    // 3. 判断返回值
    if (result.intValue() != 0) {
      return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
    }

    //获取代理对象
    proxy = (IVoucherOrderService) AopContext.currentProxy();
    //blocking queue
    //VoucherOrder voucherOrder = new VoucherOrder();
    //voucherOrder.setId(orderId);
    //voucherOrder.setUserId(UserHolder.getUser().getId());
    //voucherOrder.setVoucherId(voucherId);
    //orderTasks.add(voucherOrder);

    // 4. 返回订单id
    return Result.ok(orderId);

//    // 1. 查询优惠券信息
//    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//    // 2. 判断秒杀是否开始
//    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//      return Result.fail("秒杀尚未开始！");
//    }
//
//    // 3. 判断秒杀是否结束
//    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//      return Result.fail("秒杀已经结束！");
//    }
//
//    // 4. 判断库存是否充足
//    if (voucher.getStock() < 1) {
//      return Result.fail("库存不足！");
//    }
//
//    Long userId = UserHolder.getUser().getId();
//
//    // 原来自己定义的分布式锁simpleRedisLock，现在改为redission
//    // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//    // boolean isLock = lock.tryLock(1200L);
//
//    //redission lock
//    RLock lock = redissonClient.getLock("lock:order:" + userId);
//    boolean isLock = lock.tryLock();
//    // 判断是否获取锁成功
//    if (!isLock) {
//      return Result.fail("不允许重复下单");
//    }
//    try {
//      IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//      return proxy.createVoucherOrder(voucherId);
//    } finally {
//      // 释放锁
//      lock.unlock();
//    }
//  }
//
//  @Transactional
//  public Result createVoucherOrder(Long voucherId) {
//    Long userId = UserHolder.getUser().getId();
//    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//    if(count > 0){
//      return Result.fail("用户已达购买上限，限购一单");
//    }
//
//    // 5. 扣减库存
//    boolean success = seckillVoucherService.update()
//        .setSql("stock = stock - 1")
//        .eq("voucher_id", voucherId)
//        .gt("stock", 0)
//        .update();
//    if (!success) {
//      return Result.fail("库存不足！");
//    }
//
//    // 6. 创建订单
//    VoucherOrder voucherOrder = new VoucherOrder();
//    // 6.1 订单ID
//    long orderId = redisIdWorker.nextId("order");
//    voucherOrder.setId(orderId);
//    // 6.2 用户ID
//    voucherOrder.setUserId(userId);
//    // 6.3 优惠券ID
//    voucherOrder.setVoucherId(voucherId);
//    save(voucherOrder);
//
//    // 7. 返回订单ID
//    return Result.ok(orderId);
  }
  @Transactional
  public void createVoucherOrder(VoucherOrder voucherOrder) {
    Long userId = voucherOrder.getUserId();
    Long voucherId = voucherOrder.getVoucherId();

    // 判断一人一单
    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    if (count > 0) {
      log.error("用户已达购买上限, 限购一单");
      return;
    }

    // 扣减库存
    boolean success = seckillVoucherService.update()
        .setSql("stock = stock - 1")
        .eq("voucher_id", voucherId)
        .gt("stock", 0)
        .update();
    if (!success) {
      log.error("库存不足!");
      return;
    }

    // 创建订单
    save(voucherOrder);
  }
}

