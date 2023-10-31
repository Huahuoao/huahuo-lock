package com.huahuo.huahuolock.service.impl;


import com.huahuo.huahuolock.service.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RedisReentrantLock implements DistributedLock {
    private Logger logger = LoggerFactory.getLogger(RedisReentrantLock.class);


    private RedisTemplate redisTemplate;

    private String keyName;

    private String lockValue;

    private Integer timeout;    //锁超时时间

    public RedisReentrantLock(RedisTemplate redisTemplate, String keyName, String lockValue, Integer timeout) {
        this.redisTemplate = redisTemplate;
        this.keyName = keyName;
        this.lockValue = lockValue;
        this.timeout = timeout * 1000;      // 单位ms
    }

    public boolean tryLock(long waitTime, TimeUnit unit) throws InterruptedException {
        long start = System.currentTimeMillis();
        long end;
        long sleepTime = 1L; // 重试间隔时间，单位ms。指数增长，最大值为1024ms
        do {
            //尝试获取锁
            boolean success = tryLock(keyName, lockValue, timeout);
            if (success) {
                //成功获取锁，返回
                return true;
            }
            // 等待后继续尝试获取
            if (sleepTime < 1000L) {
                sleepTime = sleepTime << 1;
            }
            Thread.sleep(sleepTime);
            end = System.currentTimeMillis();
        } while (end - start < unit.toMillis(waitTime));
        return false;
    }

    public boolean tryLock() throws InterruptedException {
        return tryLock(timeout, TimeUnit.MILLISECONDS);
    }

    public void unlock() {
        releaseLock(keyName, lockValue, timeout);
    }

    /**
     * 通过exists判断，如果锁不存在，则设置值和过期时间，加锁成功
     * 通过hexists判断，如果锁已存在，并且锁的是当前线程，则证明是重入锁，加锁成功
     * 如果锁已存在，但锁的不是当前线程，则证明有其他线程持有锁。返回当前锁的过期时间，加锁失败
     *
     * @param key     key
     * @param value   value
     * @param timeout 超时时间
     * @return 是否加锁成功
     */
    private boolean tryLock(String key, String value, long timeout) {
        String command =
                "if (redis.call('exists', KEYS[1]) == 0) then " +                   //判断指定的key是否存在
                        "redis.call('hset', KEYS[1], ARGV[2], 1); " +                   //新增key，value为hash结构
                        "redis.call('pexpire', KEYS[1], ARGV[1]); " +                   //设置过期时间
                        "return nil; " +                                                //直接返回null，表示加锁成功
                        "end; " +
                        "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +         //判断hash中是否存在指定的建
                        "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +                //hash中指定键的值+1
                        "redis.call('pexpire', KEYS[1], ARGV[1]); " +                   //重置过期时间
                        "return nil; " +                                                //返回null，表示加锁成功
                        "end; " +
                        "return redis.call('pttl', KEYS[1]);";                              //返回key的剩余过期时间，表示加锁失败
        RedisScript<Long> redisScript = new DefaultRedisScript<>(command, Long.class);
        String time = String.valueOf(timeout);
        List<String> keyList = Collections.singletonList(key);
        return redisReentrantLockGetLuaResult(key, value, timeout, redisScript, time, keyList, redisTemplate, logger);
    }

    static boolean redisReentrantLockGetLuaResult(String key, String value, long timeout, RedisScript<Long> redisScript, String time, List<String> keyList, RedisTemplate redisTemplate, Logger logger) {
        Long result = (Long) redisTemplate.execute(redisScript, keyList, time, value);
        if (result == null) {
            logger.debug("acquire lock success, keyName:{}, lockValue:{}, timeout:{}", key, value, timeout);
            return true;
        } else {
            logger.debug("acquire lock fail, keyName:{}, lockValue:{}, ttl:{}", key, value, result);
            return false;
        }
    }


    /**
     * 如果锁已经不存在，通过publish发布锁释放的消息，解锁成功
     * 如果解锁的线程和当前锁的线程不是同一个，解锁失败，抛出异常
     * 通过hincrby递减1，先释放一次锁。若剩余次数还大于0，则证明当前锁是重入锁，刷新过期时间；若剩余次数小于0，删除key并发布锁释放的消息，解锁成功
     *
     * @param key     key
     * @param value   value
     * @param timeout 超时时间
     * @return
     */
    private void releaseLock(String key, String value, Integer timeout) {
        String command =
                "if (redis.call('hexists', KEYS[1], ARGV[2]) == 0) then " +
                        "return nil;" +                                                         //判断当前客户端之前是否已获取到锁，若没有直接返回null
                        "end; " +
                        "local counter = redis.call('hincrby', KEYS[1], ARGV[2], -1); " +           //锁重入次数-1
                        "if (counter > 0) then " +                                                  //若锁尚未完全释放，需要重置过期时间
                        "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                        "return 0; " +                                                          //返回0表示锁未完全释放
                        "else " +
                        "redis.call('del', KEYS[1]); " +                                        //若锁已完全释放，删除当前key
                        "return 1; " +                                                          //返回1表示锁已完全释放
                        "end; " +
                        "return nil;";
        RedisScript<Long> script = new DefaultRedisScript<>(command, Long.class);
        List<String> keys = Collections.singletonList(key);
        String time = String.valueOf(timeout);
        Long result = (Long) redisTemplate.execute(script, keys, time, value);
        if (result == null) {
            logger.warn("Current thread does not hold lock, keyName:{}, lockValue:{}", key, value);
            throw new RuntimeException("current thread does not hold lock");
        }
        if (result == 1) {
            logger.debug("release lock sucess, keyName:{}, lockValue:{}", key, value);
        } else {
            logger.debug("Decrease lock times sucess, keyName:{}, lockValue:{}", key, value);
        }
    }

}
