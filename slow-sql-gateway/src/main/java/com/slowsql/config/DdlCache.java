package com.slowsql.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 表结构 DDL 缓存（Redis）。
 * Key: ddl:{instanceId}:{tableName}，TTL 5min。
 */
@Component
public class DdlCache {

    private static final Logger log = LoggerFactory.getLogger(DdlCache.class);

    private static final String KEY_PREFIX = "ddl:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public DdlCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String get(String instanceId, String tableName) {
        try {
            String key = buildKey(instanceId, tableName);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                log.debug("DDL 缓存命中: {}", tableName);
            }
            return cached;
        } catch (Exception e) {
            log.debug("DDL 缓存读取失败: {}", e.getMessage());
            return null;
        }
    }

    public void put(String instanceId, String tableName, String ddl) {
        try {
            String key = buildKey(instanceId, tableName);
            redis.opsForValue().set(key, ddl, TTL);
        } catch (Exception e) {
            log.debug("DDL 缓存写入失败: {}", e.getMessage());
        }
    }

    public void evict(String instanceId, String tableName) {
        try {
            redis.opsForValue().getAndDelete(buildKey(instanceId, tableName));
        } catch (Exception e) {
            // ignore
        }
    }

    private String buildKey(String instanceId, String tableName) {
        return KEY_PREFIX + instanceId + ":" + tableName;
    }
}
