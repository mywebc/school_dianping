package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号是否合法
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        // 2. 如果不合法， 返回错误信息
        if (phoneInvalid) {
            return Result.fail("手机号格式不正确");
        }
        // 3. 如果合法，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4. 验证验证码保存到session
        session.setAttribute("code", code);
        // 5. 发送验证码(模拟的)，一般在公司会有独立的服务， 直接调用即可
        log.debug("发送验证码：{}", code);
        // 6. 返回成功
        return Result.ok();
    }
}
