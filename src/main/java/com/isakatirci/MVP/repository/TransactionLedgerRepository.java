package com.isakatirci.MVP.repository;

import com.isakatirci.MVP.entity.TransactionLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionLedgerRepository extends JpaRepository<TransactionLedger, Long> {
    Optional<TransactionLedger> findByTransactionId(String transactionId);

    java.util.List<TransactionLedger> findByStatusOrderByCreatedAtAsc(TransactionLedger.TransactionStatus status);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(CASE WHEN t.toAccountId = :accountId THEN t.amount ELSE -t.amount END), 0) FROM TransactionLedger t WHERE (t.fromAccountId = :accountId OR t.toAccountId = :accountId) AND t.status = 'COMPLETED'")
    java.math.BigDecimal calculateBalance(String accountId);
}
