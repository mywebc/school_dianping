package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.resource.StringResource;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        // session.setAttribute("code", code);
        // 4.1 后续：修改为保存到redis, 注意要设置有效期（验证吗会过期的！）
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码(模拟的)，一般在公司会有独立的服务， 直接调用即可
        log.debug("发送验证码：{}", code);
        // 6. 返回成功
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号是否合法
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(loginForm.getPhone());
        // 2. 如果不合法， 返回错误信息
        if (phoneInvalid) {
            return Result.fail("手机号格式不正确");
        }
        // 2. 校验验证码是否正确 (从session获取)
//        Object cacheCode = session.getAttribute("code");
        // 2.1 后续：修改为从redis获取
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());

        // 3.不一致，报错
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        // 4. 一致，根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        // 5. 如果用户不存在，直接注册并保存到session
        if (user == null) {
            user = createUserWithPhone(loginForm.getPhone());
        }
        // 6. 如果用户存在，直接保存到session
        // 这里注意将user 转为为userDto，去除冗余字段
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 6.1 修改： 保存到redis
        // 6.1 生成一个token (使用UUID)
        String token = UUID.randomUUID().toString(true);
        // 6.2 将user对象转为hash存储到redis
        UserDTO userDto = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDto, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // putAll 接受一个map， 所以这里要先将userDto转为map,一次性存入redis
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        // 设置有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 6.3 返回token到客户端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1. 生成用户信息
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6));
        // 2. 保存到数据库
        save(user);
        // 3. 返回用户信息
        return user;
    }
}
