package com.isakatirci.MVP.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class TwoLevelCache implements Cache {
    private final Cache localCache;
    private final Cache redisCache;

    // Circuit breaker state variables to handle runtime Redis failures
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final long COOL_DOWN_PERIOD_MS = 30_000; // 30 seconds
    
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final AtomicBoolean redisCircuitOpen = new AtomicBoolean(false);
    private volatile long lastErrorTime = 0;

    public TwoLevelCache(Cache localCache, Cache redisCache) {
        this.localCache = localCache;
        this.redisCache = redisCache;
    }

    private boolean isRedisAvailable() {
        if (redisCircuitOpen.get()) {
            long elapsed = System.currentTimeMillis() - lastErrorTime;
            if (elapsed > COOL_DOWN_PERIOD_MS) {
                // Try to close the circuit (give Redis another chance)
                log.info("Redis cool-down period elapsed. Attempting to re-enable Redis operations...");
                redisCircuitOpen.set(false);
                consecutiveErrors.set(0);
                return true;
            }
            return false;
        }
        return true;
    }

    private void handleRedisSuccess() {
        if (consecutiveErrors.get() > 0) {
            consecutiveErrors.set(0);
        }
    }

    private void handleRedisFailure(Exception e) {
        int errors = consecutiveErrors.incrementAndGet();
        lastErrorTime = System.currentTimeMillis();
        log.warn("Redis operation failed (consecutive failures: {}): {}", errors, e.getMessage());
        if (errors >= MAX_CONSECUTIVE_ERRORS) {
            log.error("Redis consecutive failures reached limit ({}). Opening circuit breaker to bypass Redis for {}ms.", 
                    MAX_CONSECUTIVE_ERRORS, COOL_DOWN_PERIOD_MS);
            redisCircuitOpen.set(true);
        }
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
        // Try L1 (Caffeine) first
        ValueWrapper wrapper = localCache.get(key);
        if (wrapper != null) {
            log.trace("L1 (Caffeine) cache HIT for key: {}", key);
            return wrapper;
        }

        // Try L2 (Redis) with circuit-breaker protection
        if (isRedisAvailable()) {
            try {
                wrapper = redisCache.get(key);
                if (wrapper != null) {
                    log.debug("L2 (Redis) cache HIT for key: {}. Writing back to L1.", key);
                    localCache.put(key, wrapper.get());
                    handleRedisSuccess();
                    return wrapper;
                }
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }

        log.trace("Cache MISS for key: {}", key);
        return null;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        // Try L1 (Caffeine) first
        T value = localCache.get(key, type);
        if (value != null) {
            log.trace("L1 (Caffeine) cache HIT for key: {}", key);
            return value;
        }

        // Try L2 (Redis) with circuit-breaker protection
        if (isRedisAvailable()) {
            try {
                value = redisCache.get(key, type);
                if (value != null) {
                    log.debug("L2 (Redis) cache HIT for key: {}. Writing back to L1.", key);
                    localCache.put(key, value);
                    handleRedisSuccess();
                    return value;
                }
            } catch (Exception e) {
                handleRedisFailure(e);
            }
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
        if (isRedisAvailable()) {
            try {
                redisCache.put(key, value);
                handleRedisSuccess();
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        if (isRedisAvailable()) {
            try {
                redisCache.putIfAbsent(key, value);
                handleRedisSuccess();
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        return localCache.putIfAbsent(key, value);
    }

    @Override
    public void evict(Object key) {
        localCache.evict(key);
        if (isRedisAvailable()) {
            try {
                redisCache.evict(key);
                handleRedisSuccess();
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
    }

    @Override
    public void clear() {
        localCache.clear();
        if (isRedisAvailable()) {
            try {
                redisCache.clear();
                handleRedisSuccess();
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
    }
}
