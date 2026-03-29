package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Override
  public Result queryById(Long id){
    //get store id from redis
    String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

    //if it exist in redis, return it
    if(StrUtil.isNotBlank(shopJson)){
      Shop shop = JSONUtil.toBean(shopJson, Shop.class);
      return Result.ok(shop);
    }
    //if not, query in database
    Shop shop = getById(id);

    //if both do not have data, failed
    if(shop == null){
      return Result.fail("store does not exist");
    }
    //store the store in redis
    String jsonStr = JSONUtil.toJsonStr(shop);
    stringRedisTemplate.opsForValue().set(
        CACHE_SHOP_KEY + id,
        jsonStr,
        CACHE_SHOP_TTL,
        TimeUnit.MINUTES
    );

    //return shop information
    return Result.ok(shop);
  }

  @Override
  @Transactional
  public Result updateShop(Shop shop){
    Long id = shop.getId();
    if(id == null){
      return Result.fail("shop id cannot be null");
    }
    //update the database
    updateById(shop);

    //delete the cache
    stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    return Result.ok();
  }
}
