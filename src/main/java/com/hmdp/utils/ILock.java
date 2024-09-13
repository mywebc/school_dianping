package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeSec 锁的持续时间
     * @return true表示成功， false表示失败
     */
    boolean tryLock(long timeSec);

    /**
     * 释放锁
     */
    void unLock();
}
