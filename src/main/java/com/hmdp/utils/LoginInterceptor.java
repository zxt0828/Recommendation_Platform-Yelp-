package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    HttpSession session = request.getSession();
    // 2. 获取 session 中的用户
    Object user = session.getAttribute("user");
    // 3. 判断用户是否存在
    if (user == null) {
      // 4. 不存在，拦截，返回 401 未授权
      response.setStatus(401);
      return false;
    }
    // 5. 存在，保存用户信息到 ThreadLocal
    UserHolder.saveUser((UserDTO)  user);
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
