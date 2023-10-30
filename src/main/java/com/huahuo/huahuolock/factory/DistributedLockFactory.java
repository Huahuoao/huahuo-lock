package com.huahuo.huahuolock.factory;


import com.huahuo.huahuolock.service.impl.RedisReentrantLock;
import com.huahuo.huahuolock.service.DistributedLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DistributedLockFactory {
    @Autowired
    @Qualifier("huahuoLockRedisTemplate")
    private RedisTemplate redisTemplate;

   @Value("${lock.expire-time}")
   Integer timeout;
    public DistributedLock newRedisReentrantLock(String keyName) {
        String lockValue = UUID.randomUUID().toString();
        return new RedisReentrantLock(redisTemplate, keyName, lockValue, timeout);
    }




//
//    //Redis可重入读写锁  mode 为 "read" "write" 两种
//    public DistributedLock newRedisReadWriteLock(String keyName, String mode) {
//        String lockValue = UUID.randomUUID().toString();
//        Integer timeout = 30;    //    // 单位s
//        return new RedisReadWriteLock(redisClient, keyName, lockValue, timeout, mode);
//    }
//
//    //mysql 可重入锁
//    public DistributedLock newMysqlReentrantLock(String keyName) {
//        String lockValue = UUID.randomUUID().toString();
//        Integer timeout = 30;
//        return new MysqlReentrantLock(keyName, lockValue, timeout);
//    }
//
//
//    //mysql 读写锁
//    public DistributedLock newMysqlReadWriteLock(String keyName, String mode) {
//        String lockValue = UUID.randomUUID().toString();
//        Integer timeout = 30;
//        return new MysqlReadWriteLock(keyName, lockValue, timeout, mode);
//    }


}