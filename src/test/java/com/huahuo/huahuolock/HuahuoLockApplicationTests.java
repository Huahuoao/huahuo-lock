package com.huahuo.huahuolock;

import com.huahuo.huahuolock.factory.DistributedLockFactory;
import com.huahuo.huahuolock.service.DistributedLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;

@SpringBootTest
class HuahuoLockApplicationTests {
    @Autowired
    RedisTemplate redisTemplate;
    @Autowired
    DistributedLockFactory distributedLockFactory;

    @Test
    void contextLoads() throws InterruptedException {
        DistributedLock key = distributedLockFactory.newRedisReentrantLock("key");
        key.tryLock();
        key.tryLock();
        key.tryLock();
        key.tryLock();
        key.tryLock();
        key.unlock();

    }

}
