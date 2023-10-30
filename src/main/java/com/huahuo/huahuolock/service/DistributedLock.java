package com.huahuo.huahuolock.service;

import java.util.concurrent.TimeUnit;

public interface DistributedLock {
    /**
     * 在有效时间内阻塞加锁，可被中断
     */
    boolean tryLock(long waitTime, TimeUnit unit) throws InterruptedException;

    /**
     * 尝试加锁
     */
    boolean tryLock() throws InterruptedException;


    /**
     * 解锁操作
     */
    void unlock();
}
