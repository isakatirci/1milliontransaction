package com.isakatirci.MVP.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class TwoLevelCacheManager implements CacheManager {
    private final CaffeineCacheManager caffeineCacheManager;
    private final RedisCacheManager redisCacheManager;
    private final boolean redisEnabled;
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(CaffeineCacheManager caffeineCacheManager, RedisCacheManager redisCacheManager, boolean redisEnabled) {
        this.caffeineCacheManager = caffeineCacheManager;
        this.redisCacheManager = redisCacheManager;
        this.redisEnabled = redisEnabled;
    }

    @Override
    public Cache getCache(String name) {
        if (!redisEnabled) {
            return caffeineCacheManager.getCache(name);
        }
        return caches.computeIfAbsent(name, n -> {
            Cache caffeineCache = caffeineCacheManager.getCache(n);
            Cache redisCache = redisCacheManager.getCache(n);
            return new TwoLevelCache(caffeineCache, redisCache);
        });
    }

    @Override
    public Collection<String> getCacheNames() {
        return caffeineCacheManager.getCacheNames();
    }
}
