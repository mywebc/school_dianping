package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取session
        HttpSession session = request.getSession();
        // 获取session中的用户
        Object user = session.getAttribute("user");
        // 判断用户是否存在
        if (user == null) {
            // 不存在拦截
            // 跳转到登录页面
            // response.sendRedirect("/login.html");
            // 401 就是未授权的意思
            response.setStatus(401);
            return false;
        }
        System.out.println("拦截器find-user = " + user);
        // 存在保存到threadLocal
        UserHolder.saveUser((UserDTO) user);
        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
