package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取session
//        HttpSession session = request.getSession();

        // 修改： 从请求头中获取token
        String token = request.getHeader("authorization");

        if (StrUtil.isBlank(token)) {
            // 401 就是未授权的意思
            response.setStatus(401);
            return false;
        }
        // 获取session中的用户
//        Object user = session.getAttribute("user");
        // 判断用户是否存在
//        if (user == null) {
//            // 不存在拦截
//            // 跳转到登录页面
//            // response.sendRedirect("/login.html");
//            // 401 就是未授权的意思
//            response.setStatus(401);
//            return false;
//        }
        // 修改：从redis中获取用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            // 401 就是未授权的意思
            response.setStatus(401);
            return false;
        }

        // 从redis取的是hash，我们需要转化为userDto
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 存在保存到threadLocal
        UserHolder.saveUser(userDTO);
        // 刷新token的有效期 (保证只要这个人再次登录有效期就会刷新)
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
