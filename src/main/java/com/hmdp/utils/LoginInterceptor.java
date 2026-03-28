package com.hmdp.utils;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import java.util.concurrent.TimeUnit;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {
  private StringRedisTemplate stringRedisTemplate;

  public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
    this.stringRedisTemplate = stringRedisTemplate;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    String token = request.getHeader("authorization");
    if(StrUtil.isBlank(token)){
      response.setStatus(401);
      return false;
    }

    String key = LOGIN_USER_KEY + token;
    Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

    if (userMap.isEmpty()) {
      response.setStatus(401);
      return false;
    }

    UserDTO userDTO = BeanUtil.fillBeanWithMap(
        userMap, new UserDTO(), false);
    // 5. 存入 ThreadLocal（不变）
    UserHolder.saveUser(userDTO);
    // 6. 刷新 token 过期时间（新增！Session 是自动续期的，Redis 需要手动）
    stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
    // 6. 放行
    return true;
  }

  @Override
  public void afterCompletion(HttpServletRequest request,
      HttpServletResponse response,
      Object handler, Exception ex) {
    // 请求结束后，清除 ThreadLocal，防止内存泄漏
    UserHolder.removeUser();
  }
}
