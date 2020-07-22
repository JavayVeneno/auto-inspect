package com.veneno.aop;

import javax.annotation.Resource;
import com.veneno.anno.AutoInspect;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.AfterReturning;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import java.util.Objects;
import java.util.ArrayList;
import org.slf4j.LoggerFactory;
import java.nio.charset.Charset;
import com.veneno.utils.MD5Utils;
import com.alibaba.fastjson.JSON;
import redis.clients.jedis.Jedis;
import org.aspectj.lang.JoinPoint;
import org.springframework.util.Assert;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisCommands;
import com.veneno.exception.IdemptException;
import org.springframework.util.StringUtils;
import java.util.concurrent.CompletableFuture;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;


@Component
@Aspect
@Order(1)
public class AutoInspectAspect {
    private static final Logger log = LoggerFactory.getLogger(AutoInspectAspect.class);

    @Value("${auto-inspect-time}")
    private long expireTime = 1500L;

    private static final String UNLOCK_LUA;

    private ThreadLocal<String> lockFlag = new ThreadLocal<>();
    private ThreadLocal<String> sessionKey = new ThreadLocal<>();

    private static final String STR_TURE = "true";

    @Value("${success-code}")
    private static final String STR_MS_CODE = "90001";

    private static final String DEFAULTMS = "已经收到您的请求，请稍后查询结果~";

    /**
     * 释放锁脚本，原子操作
     */
    static {
        UNLOCK_LUA = "if redis.call(\"get\",KEYS[1]) == ARGV[1] " +
                "then " +
                "    return redis.call(\"del\",KEYS[1]) " +
                "else " +
                "    return 0 " +
                "end ";

    }


    @Resource
    private RedisTemplate<String, Integer> redisTemplate;


    /**
     * @Description: 前置方法
     * @author: Amei
     * @date: 2020/1/21 9:34
     * @param:
     * @return:
     */

    @Before("@annotation(autoInspect)")
    public void before(JoinPoint joinPoint, AutoInspect autoInspect) throws Exception {
        String key = getKeyFromTarget(joinPoint);
        if (0L != autoInspect.expireTime()) {
            expireTime = autoInspect.expireTime();
        }
        boolean isLock = doLock(key);
        if (!isLock) {
            String msg = autoInspect.throwMessage();
            if (StringUtils.isEmpty(msg)) {
                msg = DEFAULTMS;
            }
            throw new IdemptException(msg);
        } else {
            log.info("请求已经通过...");
        }

    }


    @AfterThrowing(pointcut = "within(*.service.*) && @annotation(autoInspect)", throwing = "ex")
    public void delKey(JoinPoint joinPoint, AutoInspect autoInspect, Exception ex) throws Exception {
        if (!(ex instanceof IdemptException)) {
            String key = getSessionKey(joinPoint);
            String value = lockFlag.get();
            lockFlag.remove();
            CompletableFuture.supplyAsync(() -> {
                if (!doRelease(key, value)) {
                    log.info("release args:{} 's key:{} not success", joinPoint.getArgs(), key);
                }
                return true;
            }).exceptionally(e -> {
                log.info("release args:{} 's key:{} not success", joinPoint.getArgs(), key);
                return false;
            });

        }
    }


    /**
     * @Description: 后置方法
     * @author: Amei
     * @date: 2020/1/21 9:34
     * @param: [joinPoint, rl, rvt]
     * @return: void
     */
    @AfterReturning(pointcut = "@annotation(autoInspect)", returning = "rvt")
    public void afterMethod(JoinPoint joinPoint, AutoInspect autoInspect, Object rvt) throws Exception {
        String key = getSessionKey(joinPoint);
        String value = lockFlag.get();
        lockFlag.remove();
        CompletableFuture.supplyAsync(() -> {
            String word = JSON.toJSONString(rvt).toLowerCase();
            if (word.contains(STR_TURE) || word.contains(STR_MS_CODE)) {
                doLock(key);
            } else {
                if (!doRelease(key, value)) {
                    log.info("release args:{} 's key:{} not success", joinPoint.getArgs(), key);
                }
            }
            return true;
        }).exceptionally(e -> {
            log.info("release args:{} 's key:{} not success", joinPoint.getArgs(), key);
            return false;
        });

    }


    private String getSessionKey(JoinPoint joinPoint) throws Exception {
        String key = sessionKey.get();
        if (key == null) {
            return getKeyFromTarget(joinPoint);
        }
        sessionKey.remove();
        return key;
    }

    /**
     * @Description: 获取参数生成key
     * @author: Amei
     * @date: 2020/1/21 9:34
     * @param: [joinPoint]
     * @return: java.lang.String
     */
    private String getKeyFromTarget(JoinPoint joinPoint) throws Exception {
        StringBuilder sb = new StringBuilder();
        Object target = joinPoint.getTarget();
        String className = target.getClass().getName();
        String name = joinPoint.getSignature().getName();
        sb.append(className).append(name);
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            sb.append(JSON.toJSONString(arg));
        }
        String sk = MD5Utils.getMD5(sb.toString());
        sessionKey.set(sk);
        return sk;
    }


    /**
     * @Description: lua脚本去锁定
     * @author: Amei
     * @date: 2020/1/21 9:35
     * @param: [key]
     * @return: java.lang.Boolean
     */
    private Boolean doLock(String key) {
        Assert.notNull(key, "key must not be null ");
        try {
            String result = redisTemplate.execute((RedisCallback<String>) connection -> {
                JedisCommands commands = (JedisCommands) connection.getNativeConnection();
                String uuid = UUID.randomUUID().toString();
                lockFlag.set(uuid);
                return commands.set(key, uuid, "NX", "PX", expireTime * 1000L);
            });
            return !StringUtils.isEmpty(result);
        } catch (Exception e) {
            log.error("set redis occured an exception", e);
        }
        return false;
    }

    /**
     * @Description: lua脚本去释放
     * @author: Amei
     * @date: 2020/1/21 9:35
     * @param: [key]
     * @return: java.lang.Boolean
     */
    private Boolean doRelease(String key, String value) {
        Assert.notNull(key, "key must not be null ");
        Assert.notNull(value, "value must not be null ");
        return releaseLock(key, value);

    }


    public String get(String lockKey) {
        try {
            RedisCallback<String> callback = (connection) -> new String(Objects.requireNonNull(connection.get(lockKey.getBytes())), Charset.forName("UTF-8"));
            return redisTemplate.execute(callback);
        } catch (Exception e) {
            log.error("get redis occurred an exception", e);
        }
        return null;
    }


    private boolean releaseLock(String key, String values) {
        // 释放锁的时候，有可能因为持锁之后方法执行时间大于锁的有效期，此时有可能已经被另外一个线程持有锁，所以不能直接删除
        try {
            List<String> keys = new ArrayList<>();
            keys.add(key);
            List<String> args = new ArrayList<>();
            args.add(values);

            // 使用lua脚本删除redis中匹配value的key，可以避免由于方法执行时间过长而redis锁自动过期失效的时候误删其他线程的锁
            // spring自带的执行脚本方法中，集群模式直接抛出不支持执行脚本的异常，所以只能拿到原redis的connection来执行脚本

            Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
                Object nativeConnection = connection.getNativeConnection();
                // 集群模式和单机模式虽然执行脚本的方法一样，但是没有共同的接口，所以只能分开执行
                // 集群模式
                if (nativeConnection instanceof JedisCluster) {
                    return (Long) ((JedisCluster) nativeConnection).eval(UNLOCK_LUA, keys, args);
                }

                // 单机模式
                else if (nativeConnection instanceof Jedis) {
                    return (Long) ((Jedis) nativeConnection).eval(UNLOCK_LUA, keys, args);
                }
                return 0L;
            });
            lockFlag.remove();
            return result != null && result > 0;
        } catch (Exception e) {
            log.error("release lock occured an exception", e);
        }
        return false;
    }

    /**
     * 手动实现幂等
     *
     * @param key        手动key
     * @param expireTime 超时时间
     * @return 是否成功
     */
    public boolean idempotentByManual(String key, Long expireTime) {
        if (expireTime != null) {
            this.expireTime = expireTime;
        }
        return doLock(key);

    }

}
