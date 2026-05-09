/*
package com.isakatirci.MVP;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Ledger Service Integration Tests")
class LedgerServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionLedgerRepository transactionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        // Clean up test data
        outboxRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        // Create test accounts
        createTestAccount("alice", "10000");
        createTestAccount("bob", "10000");
        createTestAccount("charlie", "10000");
    }

    // ==================== BASIC TRANSFER TESTS ====================

    @Test
    @DisplayName("Should create transfer between two accounts")
    void shouldCreateTransfer() throws Exception {
        CreateTransferRequest request = CreateTransferRequest.builder()
                .transactionId("txn-001")
                .fromAccountId("alice")
                .toAccountId("bob")
                .amount(new BigDecimal("500"))
                .metadata("test transfer")
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fromBalance").value(9500))
                .andExpect(jsonPath("$.toBalance").value(10500));

        // Verify in database
        Account alice = accountRepository.findByAccountId("alice").orElse(null);
        Account bob = accountRepository.findByAccountId("bob").orElse(null);

        assert alice != null && alice.getBalance().compareTo(new BigDecimal("9500")) == 0;
        assert bob != null && bob.getBalance().compareTo(new BigDecimal("10500")) == 0;
    }

    @Test
    @DisplayName("Should reject transfer with insufficient balance")
    void shouldRejectInsufficientBalance() throws Exception {
        CreateTransferRequest request = CreateTransferRequest.builder()
                .transactionId("txn-002")
                .fromAccountId("alice")
                .toAccountId("bob")
                .amount(new BigDecimal("15000"))
                .metadata("overspend")
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is5xxServerError());

        // Verify no transfer occurred
        assert transactionRepository.findByTransactionId("txn-002").isEmpty();
    }

    // ==================== IDEMPOTENCY TESTS ====================

    @Test
    @DisplayName("Should be idempotent - same transaction ID returns same result")
    void shouldBeIdempotent() throws Exception {
        CreateTransferRequest request = CreateTransferRequest.builder()
                .transactionId("txn-idempotent")
                .fromAccountId("alice")
                .toAccountId("bob")
                .amount(new BigDecimal("100"))
                .metadata("idempotency test")
                .build();

        String requestBody = objectMapper.writeValueAsString(request);

        // First call
        MvcResult result1 = mockMvc.perform(post("/api/v1/transfer")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String response1 = result1.getResponse().getContentAsString();
        BigDecimal balance1 = objectMapper.readTree(response1).get("fromBalance").decimalValue();

        // Second call - identical
        MvcResult result2 = mockMvc.perform(post("/api/v1/transfer")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String response2 = result2.getResponse().getContentAsString();
        BigDecimal balance2 = objectMapper.readTree(response2).get("fromBalance").decimalValue();

        // Should be exactly same
        assert balance1.compareTo(balance2) == 0;
        assert balance1.compareTo(new BigDecimal("9900")) == 0;

        // Only one transaction in database
        assert transactionRepository.findByTransactionId("txn-idempotent").isPresent();
    }

    // ==================== CONCURRENT TRANSFER TESTS ====================

    @Test
    @DisplayName("Should handle concurrent transfers without deadlock")
    void shouldHandleConcurrentTransfersWithoutDeadlock() throws Exception {
        int threadCount = 10;
        int transfersPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    for (int j = 0; j < transfersPerThread; j++) {
                        CreateTransferRequest request = CreateTransferRequest.builder()
                                .transactionId(String.format("txn-concurrent-%d-%d", threadId, j))
                                .fromAccountId("alice")
                                .toAccountId("bob")
                                .amount(new BigDecimal("10"))
                                .metadata("concurrent test")
                                .build();

                        mockMvc.perform(post("/api/v1/transfer")
                                        .contentType("application/json")
                                        .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor));
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        // Verify totals
        Account alice = accountRepository.findByAccountId("alice").orElse(null);
        Account bob = accountRepository.findByAccountId("bob").orElse(null);

        BigDecimal expectedTransfer = new BigDecimal(threadCount * transfersPerThread * 10);
        assert alice != null && alice.getBalance().compareTo(new BigDecimal("10000").subtract(expectedTransfer)) == 0;
        assert bob != null && bob.getBalance().compareTo(new BigDecimal("10000").add(expectedTransfer)) == 0;

        // All transactions should be completed
        long completedTxns = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionLedger.TransactionStatus.COMPLETED)
                .count();
        assert completedTxns == threadCount * transfersPerThread;
    }

    @Test
    @DisplayName("Should prevent circular deadlocks in concurrent bidirectional transfers")
    void shouldPreventCircularDeadlocks() throws Exception {
        int iterations = 20;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            final int iteration = i;

            // Alice -> Bob
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    CreateTransferRequest request = CreateTransferRequest.builder()
                            .transactionId(String.format("txn-ab-%d", iteration))
                            .fromAccountId("alice")
                            .toAccountId("bob")
                            .amount(new BigDecimal("50"))
                            .metadata("bidirectional test")
                            .build();

                    mockMvc.perform(post("/api/v1/transfer")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(request)))
                            .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor));

            // Bob -> Alice
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    CreateTransferRequest request = CreateTransferRequest.builder()
                            .transactionId(String.format("txn-ba-%d", iteration))
                            .fromAccountId("bob")
                            .toAccountId("alice")
                            .amount(new BigDecimal("30"))
                            .metadata("bidirectional test")
                            .build();

                    mockMvc.perform(post("/api/v1/transfer")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(request)))
                            .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        // Total should be balanced
        Account alice = accountRepository.findByAccountId("alice").orElse(null);
        Account bob = accountRepository.findByAccountId("bob").orElse(null);

        BigDecimal aliceNet = new BigDecimal("10000").subtract(new BigDecimal(iterations * 50))
                .add(new BigDecimal(iterations * 30));
        BigDecimal bobNet = new BigDecimal("10000").add(new BigDecimal(iterations * 50))
                .subtract(new BigDecimal(iterations * 30));

        assert alice != null && alice.getBalance().compareTo(aliceNet) == 0;
        assert bob != null && bob.getBalance().compareTo(bobNet) == 0;
    }

    // ==================== OUTBOX TESTS ====================

    @Test
    @DisplayName("Should create outbox event for each transfer")
    void shouldCreateOutboxEvent() throws Exception {
        CreateTransferRequest request = CreateTransferRequest.builder()
                .transactionId("txn-outbox-001")
                .fromAccountId("alice")
                .toAccountId("bob")
                .amount(new BigDecimal("200"))
                .metadata("outbox test")
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Verify outbox event created
        List<Outbox> events = outboxRepository.findByStatusOrderByCreatedAtAsc(Outbox.OutboxStatus.PENDING);
        assert events.size() >= 1;

        Outbox event = events.get(0);
        assert event.getEventType().equals("TRANSFER_COMPLETED");
        assert event.getPayload().contains("txn-outbox-001");
        assert event.getStatus() == Outbox.OutboxStatus.PENDING;
    }

    // ==================== ACCOUNT BALANCE TESTS ====================

    @Test
    @DisplayName("Should return correct account balance")
    void shouldReturnAccountBalance() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/alice/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("alice"))
                .andExpect(jsonPath("$.balance").value(10000));
    }

    @Test
    @DisplayName("Should return 404 for non-existent account")
    void shouldReturn404ForNonExistentAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/nonexistent/balance"))
                .andExpect(status().isNotFound());
    }

    // ==================== HEALTH CHECK TESTS ====================

    @Test
    @DisplayName("Should return health status")
    void shouldReturnHealthStatus() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ==================== HELPER METHODS ====================

    private void createTestAccount(String accountId, String initialBalance) {
        try {
            mockMvc.perform(post("/api/v1/accounts")
                            .param("accountId", accountId)
                            .param("initialBalance", initialBalance))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}*/
