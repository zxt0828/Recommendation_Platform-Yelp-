package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

  private final StringRedisTemplate stringRedisTemplate;

  public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
    this.stringRedisTemplate = stringRedisTemplate;
  }

  @Override
  public Result sendCode(String phone, HttpSession session) {
    //check valid phone number
    if(RegexUtils.isPhoneInvalid(phone)) {
      return Result.fail("wrong phone number");
    }

    //produce a verification code
    String code = RandomUtil.randomNumbers(6);

    //store the code to session -> change to store in redis
    //session.setAttribute("code", code);
    stringRedisTemplate.opsForValue().set(
        LOGIN_CODE_KEY + phone,
        code,
        LOGIN_CODE_TTL,
        TimeUnit.MINUTES
    );

    //send the code
    log.debug("发送短信验证码成功，验证码：{}", code);
    return Result.ok();
  }

  @Override
  public Result login(LoginFormDTO loginForm, HttpSession session) {
    //verify phone number
    String phone = loginForm.getPhone();
    if(RegexUtils.isPhoneInvalid(phone)) {
      return Result.fail("wrong phone number");
    }

    //verify the verification code
    String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
    String code = loginForm.getCode();
    if(cacheCode == null || !cacheCode.equals(code)) {
      return Result.fail("wrong code");
    }

    //find the user from database
    User user = query().eq("phone", phone).one();

    //if user do not exist
    if(user == null){
      user = createUserWithPhone(phone);
    }

    //store session -> store user information into redis
    //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
    //生成token
    String token = UUID.randomUUID().toString(true);
    //user转userDTO转map
    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
    Map<String, Object> userMap = BeanUtil.beanToMap(
        userDTO,
        new HashMap<>(),
        CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((fieldName, fieldValue) ->
            fieldValue.toString()));
    //存入redis
    String tokenKey = LOGIN_USER_KEY + token;
    stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
    //设置过期时间
    stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
    //返回
    return Result.ok(token);

  }
  // a method : if user not exist, create a new user with phone number
  private User createUserWithPhone(String phone){
    User user = new User();
    user.setPhone(phone);
    user.setNickName("user" + RandomUtil.randomNumbers(6));
    save(user);
    return user;
  }


}
