package com.isakatirci.MVP.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.isakatirci.MVP.entity.Url;
import com.isakatirci.MVP.repository.UrlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
@Slf4j
public class CacheConfig {

    @Bean
    public CaffeineCacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5)));
        return manager;
    }

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .entryTtl(Duration.ofMinutes(10));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    @Bean
    @Primary
    public CacheManager cacheManager(CaffeineCacheManager caffeineCacheManager, RedisCacheManager redisCacheManager, RedisConnectionFactory connectionFactory) {
        boolean redisAvailable = false;
        try {
            log.info("Checking Redis connectivity at startup...");
            connectionFactory.getConnection().ping();
            redisAvailable = true;
            log.info("Redis is available. Caching will run in L1 Caffeine + L2 Redis mode.");
        } catch (Exception e) {
            log.warn("Redis is NOT available at startup. Caching will run in L1-only (Caffeine) fallback mode: {}", e.getMessage());
        }
        return new TwoLevelCacheManager(caffeineCacheManager, redisCacheManager, redisAvailable);
    }

    @Bean
    public CommandLineRunner initDatabase(UrlRepository urlRepository) {
        return args -> {
            if (urlRepository.count() == 0) {
                log.info("Url database table is empty. Pre-populating redirect records...");
                urlRepository.saveAll(List.of(
                        Url.builder().shortCode("TR").originalUrl("https://en.wikipedia.org/wiki/Turkey").build(),
                        Url.builder().shortCode("US").originalUrl("https://en.wikipedia.org/wiki/United_States").build(),
                        Url.builder().shortCode("DE").originalUrl("https://en.wikipedia.org/wiki/Germany").build(),
                        Url.builder().shortCode("GB").originalUrl("https://en.wikipedia.org/wiki/United_Kingdom").build(),
                        Url.builder().shortCode("FR").originalUrl("https://en.wikipedia.org/wiki/France").build(),
                        Url.builder().shortCode("JP").originalUrl("https://en.wikipedia.org/wiki/Japan").build(),
                        Url.builder().shortCode("CA").originalUrl("https://en.wikipedia.org/wiki/Canada").build(),
                        Url.builder().shortCode("AU").originalUrl("https://en.wikipedia.org/wiki/Australia").build(),
                        Url.builder().shortCode("IT").originalUrl("https://en.wikipedia.org/wiki/Italy").build(),
                        Url.builder().shortCode("ES").originalUrl("https://en.wikipedia.org/wiki/Spain").build(),
                        Url.builder().shortCode("NL").originalUrl("https://en.wikipedia.org/wiki/Netherlands").build(),
                        Url.builder().shortCode("CH").originalUrl("https://en.wikipedia.org/wiki/Switzerland").build(),
                        Url.builder().shortCode("SE").originalUrl("https://en.wikipedia.org/wiki/Sweden").build(),
                        Url.builder().shortCode("NO").originalUrl("https://en.wikipedia.org/wiki/Norway").build(),
                        Url.builder().shortCode("DK").originalUrl("https://en.wikipedia.org/wiki/Denmark").build(),
                        Url.builder().shortCode("FI").originalUrl("https://en.wikipedia.org/wiki/Finland").build()
                ));
                log.info("Redirect records pre-populated successfully.");
            }
        };
    }
}
