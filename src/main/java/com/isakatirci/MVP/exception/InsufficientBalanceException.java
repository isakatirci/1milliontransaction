package com.isakatirci.MVP.exception;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String accountId) {
        super("Insufficient balance for account: " + accountId);
    }
}
