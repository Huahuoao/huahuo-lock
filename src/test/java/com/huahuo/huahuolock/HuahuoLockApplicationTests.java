package com.huahuo.huahuolock;

import com.huahuo.huahuolock.factory.DistributedLockFactory;
import com.huahuo.huahuolock.service.DistributedLock;
import com.huahuo.huahuolock.service.impl.RedisReentrantLock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;

@SpringBootTest
class HuahuoLockApplicationTests {
    @Autowired
    RedisTemplate redisTemplate;
    @Autowired
    DistributedLockFactory lockFactory;
    private Logger logger = LoggerFactory.getLogger(HuahuoLockApplicationTests.class);

    @Test
    void testRedisReentrantLock() throws InterruptedException {
        String keyName = "RedisReentrantLock";
        DistributedLock lock = lockFactory.newRedisReentrantLock(keyName);
        // 加锁
        Boolean isLocked = lock.tryLock();
        if (isLocked) {
            logger.info("acquire lock success, keyName:{}", keyName);
            try {
                if (lock.tryLock()) {
                    // 这里写需要处理业务的业务代码
                    logger.info("reentrant lock success, keyName:{}", keyName);
                    logger.info("do something.");
                    Thread.sleep(3000);
                }
            } finally {
                // 释放锁
                lock.unlock();
                lock.unlock();
                logger.info("release lock success, keyName:{}", keyName);
            }
        } else {
            logger.info("acquire lock fail, keyName:{}", keyName);
        }
        Assertions.assertTrue(isLocked);
    }


    @Test
    void testRedisReadWriteLock() throws InterruptedException {
        String keyName = "RedisReadWriteLock";
        DistributedLock lock = lockFactory.newRedisReadWriteLock(keyName, "write");
        // 加锁
        Boolean isLocked = lock.tryLock();
        if (isLocked) {
            logger.info("acquire lock success, keyName:{}", keyName);
            try {
                if (lock.tryLock()) {
                    // 这里写需要处理业务的业务代码
                    logger.info("reentrant lock success, keyName:{}", keyName);
                    logger.info("do something.");
                    Thread.sleep(3000);
                }
            } finally {
                // 释放锁
                lock.unlock();
                lock.unlock();
                logger.info("release lock success, keyName:{}", keyName);
            }
        } else {
            logger.info("acquire lock fail, keyName:{}", keyName);
        }
        Assertions.assertTrue(isLocked);
    }

}
