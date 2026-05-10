package com.isakatirci.MVP.controller;

import com.isakatirci.MVP.dto.CreateAccountRequest;
import com.isakatirci.MVP.dto.CreateTransferRequest;
import com.isakatirci.MVP.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
@RequiredArgsConstructor
@Slf4j
public class StressTestController {

    private final LedgerService ledgerService;
    private final LedgerController ledgerController;

    @GetMapping("/stress-test")
    public String stressTestPage(Model model) {
        return "stress-test";
    }

    @PostMapping("/api/v1/debug/start-stress-test")
    @ResponseBody
    public ResponseEntity<String> startStressTest(@RequestParam(defaultValue = "10000") int count) {
        // Reset DB
        ledgerService.resetDatabase();

        // Setup Accounts
        ledgerController.createAccount(new CreateAccountRequest("ACC001", new BigDecimal("7000.00")));
        ledgerController.createAccount(new CreateAccountRequest("ACC002", new BigDecimal("7000.00")));

        // Start background thread to push messages to Kafka
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        for (int i = 0; i < count; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String idempotencyKey = UUID.randomUUID().toString();
                    CreateTransferRequest req = new CreateTransferRequest();
                    req.setAmount(new BigDecimal("5.00"));
                    req.setMetadata("stress-test-" + index);

                    if (index % 2 == 0) {
                        req.setFromAccountId("ACC001");
                        req.setToAccountId("ACC002");
                    } else {
                        req.setFromAccountId("ACC002");
                        req.setToAccountId("ACC001");
                    }

                    ledgerService.createTransfer(idempotencyKey, req);
                } catch (Exception e) {
                    log.error("Error pushing transfer", e);
                }
            });
        }

        return ResponseEntity.ok("Started " + count + " transactions");
    }
}
