package com.isakatirci.MVP.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
@Slf4j
public class RequestCoalescer {
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    public String coalesce(String key, Supplier<String> loader) {
        CompletableFuture<String> promise = new CompletableFuture<>();
        CompletableFuture<String> existing = inflight.putIfAbsent(key, promise);

        if (existing != null) {
            log.info("Request coalesced: waiting for inflight request for key: {}", key);
            try {
                return existing.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw e;
            }
        }

        try {
            String result = loader.get();
            promise.complete(result);
            return result;
        } catch (Throwable t) {
            promise.completeExceptionally(t);
            throw t;
        } finally {
            inflight.remove(key);
        }
    }
}
