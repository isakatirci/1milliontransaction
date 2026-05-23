package com.isakatirci.MVP.controller;

import com.isakatirci.MVP.dto.CreateAccountRequest;
import com.isakatirci.MVP.dto.CreateTransferRequest;
import com.isakatirci.MVP.dto.TransferResponse;
import com.isakatirci.MVP.entity.Account;
import com.isakatirci.MVP.entity.TransactionLedger;
import com.isakatirci.MVP.repository.AccountRepository;
import com.isakatirci.MVP.repository.TransactionLedgerRepository;
import com.isakatirci.MVP.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
@Slf4j
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final TransactionLedgerRepository transactionRepository;

    private final java.util.concurrent.Executor transferExecutor;

    /**
     * Create a transfer between two accounts.
     * Requires Idempotency-Key header for exactly-once semantics.
     * Processes asynchronously to free up Tomcat threads.
     */
    @PostMapping("/transfer")
    public java.util.concurrent.CompletableFuture<ResponseEntity<TransferResponse>> createTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                TransferResponse response = ledgerService.createTransfer(idempotencyKey, request);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            } catch (Exception e) {
                log.error("Async transfer failed: {}", e.getMessage());
                // In a real app, you'd handle specific exceptions to return correct HTTP codes
                throw new RuntimeException(e);
            }
        }, transferExecutor);
    }
    //kımplii-tıbıl FYU-çır
    public CompletableFuture<ResponseEntity<String>> test(){
        return CompletableFuture.supplyAsync(() -> ResponseEntity.status(HttpStatus.OK).body("Test"));
    }

    /**
     * Get transfer details by transaction ID.
     */
    @GetMapping("/transfers/{transactionId}")
    @Transactional(readOnly = true)
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable String transactionId) {
        return transactionRepository.findByTransactionId(transactionId)
                .map(txn -> {
                    Account from = accountRepository.findByAccountId(txn.getFromAccountId()).orElse(null);
                    Account to = accountRepository.findByAccountId(txn.getToAccountId()).orElse(null);

                    TransferResponse response = TransferResponse.builder()
                            .transactionId(txn.getTransactionId())
                            .status(txn.getStatus().toString())
                            .fromBalance(from != null ? from.getBalance() : null)
                            .toBalance(to != null ? to.getBalance() : null)
                            .timestamp(System.currentTimeMillis())
                            .build();
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get account balance.
     */
    @GetMapping("/accounts/{accountId}/balance")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        return accountRepository.findByAccountId(accountId)
                .map(account -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("accountId", account.getAccountId());
                    response.put("balance", account.getBalance());
                    response.put("timestamp", System.currentTimeMillis());
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new account. Body-based request.
     */
    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = Account.builder()
                .accountId(request.getAccountId())
                .balance(request.getInitialBalance())
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", account.getAccountId());
        response.put("balance", account.getBalance());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * RESET DATABASE (Debug only). Truncates all tables.
     */
    @DeleteMapping("/debug/reset")
    public ResponseEntity<Void> resetDatabase() {
        ledgerService.resetDatabase();
        return ResponseEntity.noContent().build();
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("database", "CONNECTED");
        response.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}
