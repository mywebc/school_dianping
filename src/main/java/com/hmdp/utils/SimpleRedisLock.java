package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String kEY_PREFIX = "lock:";

    @Override
    public boolean tryLock(long timeSec) {
        // 线程id
        long threadId = Thread.currentThread().getId();
        // 获取锁, 注意这里的value要有线程的id来作标识
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(kEY_PREFIX + name, threadId + "", timeSec, TimeUnit.SECONDS);
        // 这里会发生拆箱，有可能会有问题， 所以下面这么写为了防止出问题
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.delete(kEY_PREFIX + name);
    }
}
