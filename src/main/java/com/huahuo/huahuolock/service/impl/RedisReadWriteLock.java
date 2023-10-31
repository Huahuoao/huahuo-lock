package com.huahuo.huahuolock.service.impl;

import com.huahuo.huahuolock.service.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.huahuo.huahuolock.service.impl.RedisReentrantLock.redisReentrantLockGetLuaResult;


public class RedisReadWriteLock implements DistributedLock {
    private Logger logger = LoggerFactory.getLogger(RedisReadWriteLock.class);

    private RedisTemplate redisTemplate;

    private String keyName;

    private String lockValue;

    private Integer timeout;    //锁超时时间
    private String mode;

    public RedisReadWriteLock() {
    }

    public RedisReadWriteLock(RedisTemplate redisTemplate, String keyName, String lockValue, Integer timeout, String mode) {
        this.redisTemplate = redisTemplate;
        this.keyName = keyName;
        this.lockValue = lockValue;
        this.mode = mode;
        this.timeout = timeout * 1000;
    }

    /**
     * @param waitTime 等待时间
     * @param unit     单位
     * @return
     * @throws InterruptedException 重复尝试获取锁
     */
    @Override
    public boolean tryLock(long waitTime, TimeUnit unit) throws InterruptedException {
        long start = System.currentTimeMillis();
        long end;
        long sleepTime = 1L; // 重试间隔时间，单位ms。指数增长，最大值为1024ms
        do {
            //尝试获取锁
            boolean success = tryLock(keyName, lockValue, timeout, mode);
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


    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * @param key     锁的名字
     * @param value   锁的内容
     * @param timeout 超时时间
     * @param mode    "read" or "write"
     * @return
     */
    public boolean tryLock(String key, String value, Integer timeout, String mode) {
        if (mode.equals("read")) {
            String command = "if redis.call('exists', KEYS[2]) == 1 then "
                    + "    return nil; "   // 如果存在写锁，直接加锁失败。
                    + "else "
                    + "    if redis.call('exists', KEYS[1]) == 0 then "                    // 判断指定的key是否存在
                    + "        redis.call('HSET', KEYS[1], ARGV[2], 1) "                    // 不存在新增key，value为hash结构
                    + "        redis.call('PEXPIRE', KEYS[1], ARGV[1]) "                    // 设置过期时间
                    + "    else "
                    + "        if redis.call('HEXISTS', KEYS[1], ARGV[2]) == 1 then "       // key存在说明已经有读锁创建了，接下来判断这个锁是重入锁，还是其他线程的读锁
                    + "            redis.call('HINCRBY', KEYS[1], ARGV[2], 1) "             // hash中指定键的值+1
                    + "            redis.call('PEXPIRE', KEYS[1], ARGV[1]) "                // 重置过期时间
                    + "            return 1; "
                    + "        else "                                                      // 不是重入锁，新锁
                    + "            redis.call('HSET', KEYS[1], ARGV[2], 1) "
                    + "            redis.call('PEXPIRE', KEYS[1], ARGV[1]) "
                    + "        end "
                    + "    end "
                    + "    return 1; "                                                 // 直接返回1，表示加锁成功
                    + "end";
            //list传入 key分为两种 第一种 id 第二种 id-mode。一起创建一起删除

            ArrayList<String> keys = new ArrayList<>();
            keys.add("read-" + key);
            keys.add("write-" + key);
            RedisScript<Long> script = new DefaultRedisScript<>(command, Long.class);
            String time = timeout.toString();
            Long result = (Long) redisTemplate.execute(script, keys, time, value);
            if (result == null) {
                logger.debug("acquire lock fail, keyName:{}, lockValue:{}, ttl:{}", key, value, result);
                return false;
            } else {
                logger.debug("acquire lock success, keyName:{}, lockValue:{}, timeout:{}", key, value, timeout);
                return true;
            }

        }
        if (mode.equals("write")) {
            String command = "if redis.call('exists', KEYS[2]) == 1 then "
                    + "    return 1; " + // 如果存在读锁，直接加锁失败。
                    "end; " +
                    "if (redis.call('exists', KEYS[1]) == 0) then " +                    //判断指定的key是否存在
                    "    redis.call('hset', KEYS[1], ARGV[2], 1); " +                   //新增key，value为hash结构
                    "    redis.call('pexpire', KEYS[1], ARGV[1]); " +                   //设置过期时间
                    "    return nil; " +                                                //直接返回null，表示加锁成功
                    "end; " +
                    "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +         //判断hash中是否存在指定的建
                    "    redis.call('hincrby', KEYS[1], ARGV[2], 1); " +                //hash中指定键的值+1
                    "    redis.call('pexpire', KEYS[1], ARGV[1]); " +                   //重置过期时间
                    "    return nil; " +                                                //返回null，表示加锁成功
                    "end; " +
                    "return redis.call('pttl', KEYS[1]);";                              //返回key的剩余过期时间，表示加锁失败

            List<String> keys = new ArrayList<>();
            keys.add("write-" + key);
            keys.add("read-" + key);
            RedisScript<Long> script = new DefaultRedisScript<>(command, Long.class);
            String time = timeout.toString();
            return redisReentrantLockGetLuaResult(key, value, timeout, script, time, keys, redisTemplate, logger);
        }
        logger.debug("lock mode is incorrect,expected value is \"read\" or \"write\"");
        return false;
    }

    @Override
    public void unlock() {
        releaseLock(keyName, lockValue, timeout, mode);
    }

    /**
     * @param key     锁的名称
     * @param value   锁的内容
     * @param timeout 超时时间
     * @param mode    读写锁类型
     */
    private void releaseLock(String key, String value, Integer timeout, String mode) {
        if (mode.equals("read")) {
            String command = "if (redis.call('hexists', KEYS[1], ARGV[2]) == 0) then " +
                    "    return nil; " +                                                       // 判断当前客户端之前是否已获取到锁，若没有直接返回null
                    "end; " +
                    "local counter = redis.call('hincrby', KEYS[1], ARGV[2], -1); " +           // 锁重入次数-1
                    "if (counter > 0) then " +                                                  // 若锁尚未完全释放，需要重置过期时间
                    "    redis.call('pexpire', KEYS[1], ARGV[1]); " +
                    "    return 0; " +                                                          // 返回0表示锁未完全释放
                    "else " +
                    "    redis.call('hdel', KEYS[1], ARGV[2]); " +     //如果hash长度还大于0说明还有读锁在里面
                    "    if redis.call('hlen', KEYS[1]) > 0 then " +
                    "        return 0; " +
                    "    end; " +
                    "    return 1; " +                                                          // 返回1表示锁已完全释放
                    "end; " +
                    "return nil;";
            List<String> keys = Collections.singletonList("read-" + key);
            RedisScript<Long> script = new DefaultRedisScript<>(command, Long.class);
            String time = timeout.toString();
            Long result = (Long) redisTemplate.execute(script, keys, time, value);
            if (result == null) {
                logger.warn("Current thread does not hold lock, keyName:{}, lockValue:{}", key, value);
                throw new RuntimeException("current thread does not hold lock");
            }
            if (result == 0) {
                logger.debug("release lock sucess, keyName:{}, lockValue:{}", key, value);
            } else {
                logger.debug("Decrease lock times sucess, keyName:{}, lockValue:{}", key, value);
            }
        } else if (mode.equals("write")) {
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

            List<String> keys = Collections.singletonList("write-" + key);
            RedisScript<Long> script = new DefaultRedisScript<>(command, Long.class);
            String time = timeout.toString();
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
}
