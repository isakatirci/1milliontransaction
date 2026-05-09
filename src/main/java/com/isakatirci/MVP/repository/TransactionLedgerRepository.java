package com.isakatirci.MVP.repository;

import com.isakatirci.MVP.entity.TransactionLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionLedgerRepository extends JpaRepository<TransactionLedger, Long> {
    Optional<TransactionLedger> findByTransactionId(String transactionId);
}
