package com.isakatirci.MVP.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency key conflict: same key used with different request body: " + key);
    }
}
