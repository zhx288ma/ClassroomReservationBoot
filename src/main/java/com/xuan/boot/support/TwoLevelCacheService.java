package com.xuan.boot.support;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class TwoLevelCacheService {
    private static final String NULL_VALUE = "__NULL__";

    private final Cache<String, String> localCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public TwoLevelCacheService(StringRedisTemplate stringRedisTemplate,
                                ObjectMapper objectMapper,
                                @Value("${reservation.local-cache-ttl-minutes:5}") long localCacheTtlMinutes,
                                @Value("${reservation.local-cache-maximum-size:10000}") long maximumSize) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(localCacheTtlMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    public <T> T getObject(String key, Class<T> clazz, Duration redisTtl, Supplier<T> loader) {
        return get(key, objectMapper.getTypeFactory().constructType(clazz), redisTtl, loader);
    }

    public <T> List<T> getList(String key, Class<T> clazz, Duration redisTtl, Supplier<List<T>> loader) {
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
        return get(key, type, redisTtl, loader);
    }

    public Map<String, Object> getMap(String key, Duration redisTtl, Supplier<Map<String, Object>> loader) {
        JavaType type = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        return get(key, type, redisTtl, loader);
    }

    public <T> T get(String key, JavaType type, Duration redisTtl, Supplier<T> loader) {
        String localValue = localCache.getIfPresent(key);
        if (localValue != null) {
            return deserialize(localValue, type);
        }
        String redisValue = stringRedisTemplate.opsForValue().get(key);
        if (redisValue != null) {
            localCache.put(key, redisValue);
            return deserialize(redisValue, type);
        }
        T loaded = loader.get();
        String serialized = serialize(loaded);
        localCache.put(key, serialized);
        stringRedisTemplate.opsForValue().set(key, serialized, redisTtl);
        return loaded;
    }

    public void evict(String key) {
        localCache.invalidate(key);
        stringRedisTemplate.delete(key);
    }

    public void evictByPrefix(String prefix) {
        localCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
        Set<String> keys = stringRedisTemplate.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estimatedSize", localCache.estimatedSize());
        result.put("hitCount", localCache.stats().hitCount());
        result.put("missCount", localCache.stats().missCount());
        result.put("hitRate", localCache.stats().hitRate());
        result.put("evictionCount", localCache.stats().evictionCount());
        return result;
    }

    private <T> T deserialize(String value, JavaType type) {
        if (NULL_VALUE.equals(value)) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("二级缓存反序列化失败", exception);
        }
    }

    private String serialize(Object value) {
        if (value == null) {
            return NULL_VALUE;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("二级缓存序列化失败", exception);
        }
    }
}
