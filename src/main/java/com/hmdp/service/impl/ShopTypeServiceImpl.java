package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Override
  public Result queryTypeList(){
    //get token from redis
    String typeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

    //check whether it is empty
    if(StrUtil.isNotBlank(typeJson)){
      List<ShopType>  typeList = JSONUtil.toList(typeJson, ShopType.class);
      return Result.ok(typeList);
    }

    //if empty, query from database
    List<ShopType> typeList = query().orderByAsc("sort").list();

    //if database also do not have this list
    if(typeList == null || typeList.isEmpty()){
      return Result.fail("do not exist this shop type");
    }

    //store in redis
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));

    return Result.ok(typeList);
  }
}
