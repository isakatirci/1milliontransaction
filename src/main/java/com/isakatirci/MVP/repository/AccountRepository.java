package com.isakatirci.MVP.repository;

import com.isakatirci.MVP.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountId(String accountId);

    @Query(value = "SELECT * FROM accounts WHERE account_id = ?1 FOR UPDATE", nativeQuery = true)
    Optional<Account> findByAccountIdWithLock(String accountId);
}
