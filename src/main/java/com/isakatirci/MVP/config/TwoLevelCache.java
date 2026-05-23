package com.isakatirci.MVP.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import java.util.concurrent.Callable;

@Slf4j
public class TwoLevelCache implements Cache {
    private final Cache localCache;
    private final Cache redisCache;

    public TwoLevelCache(Cache localCache, Cache redisCache) {
        this.localCache = localCache;
        this.redisCache = redisCache;
    }

    @Override
    public String getName() {
        return localCache.getName();
    }

    @Override
    public Object getNativeCache() {
        return localCache.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        // Try L1 (Caffeine)
        ValueWrapper wrapper = localCache.get(key);
        if (wrapper != null) {
            log.trace("L1 (Caffeine) cache HIT for key: {}", key);
            return wrapper;
        }

        // Try L2 (Redis) with resilience
        try {
            wrapper = redisCache.get(key);
            if (wrapper != null) {
                log.debug("L2 (Redis) cache HIT for key: {}. Writing back to L1.", key);
                localCache.put(key, wrapper.get());
                return wrapper;
            }
        } catch (Exception e) {
            log.warn("Redis is unavailable during cache GET for key {}: {}", key, e.getMessage());
        }

        log.trace("Cache MISS for key: {}", key);
        return null;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        T value = localCache.get(key, type);
        if (value != null) {
            log.trace("L1 (Caffeine) cache HIT for key: {}", key);
            return value;
        }

        try {
            value = redisCache.get(key, type);
            if (value != null) {
                log.debug("L2 (Redis) cache HIT for key: {}. Writing back to L1.", key);
                localCache.put(key, value);
                return value;
            }
        } catch (Exception e) {
            log.warn("Redis is unavailable during cache GET for key {}: {}", key, e.getMessage());
        }

        return null;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }

        T value;
        try {
            value = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }

        put(key, value);
        return value;
    }

    @Override
    public void put(Object key, Object value) {
        localCache.put(key, value);
        try {
            redisCache.put(key, value);
        } catch (Exception e) {
            log.warn("Redis is unavailable during cache PUT for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        try {
            redisCache.putIfAbsent(key, value);
        } catch (Exception e) {
            log.warn("Redis is unavailable during cache putIfAbsent for key {}: {}", key, e.getMessage());
        }
        return localCache.putIfAbsent(key, value);
    }

    @Override
    public void evict(Object key) {
        localCache.evict(key);
        try {
            redisCache.evict(key);
        } catch (Exception e) {
            log.warn("Redis is unavailable during cache EVICT for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void clear() {
        localCache.clear();
        try {
            redisCache.clear();
        } catch (Exception e) {
            log.warn("Redis is unavailable during cache CLEAR: {}", e.getMessage());
        }
    }
}
