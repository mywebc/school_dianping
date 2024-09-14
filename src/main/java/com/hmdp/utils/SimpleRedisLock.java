package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String kEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 静态加载
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 指定位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 指定类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeSec) {
        // 线程id - 修改为用UUID来拼接（因为不同服务器上的线程id有可能一样）
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁, 注意这里的value要有线程的id来作标识
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(kEY_PREFIX + name, threadId, timeSec, TimeUnit.SECONDS);
        // 这里会发生拆箱，有可能会有问题， 所以下面这么写为了防止出问题
        return Boolean.TRUE.equals(success);
    }

    // 改造： 使用lua脚本，保证原子性
    @Override
    public void unLock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(kEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }

//    @Override
//    public void unLock() {
//        // 获取线程id
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取当前的锁的标识
//        String id = stringRedisTemplate.opsForValue().get(kEY_PREFIX + name);
//
//        if (threadId.equals(id)) {
//            // 标识一样才删除锁
//            stringRedisTemplate.delete(kEY_PREFIX + name);
//        }
//    }
}
