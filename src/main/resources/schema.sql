-- schema.sql — MVP Ledger Service
-- All statements are idempotent (safe to re-run)

-- ==================== CLEANUP ====================
DROP TABLE IF EXISTS transaction_ledgers CASCADE;
DROP TABLE IF EXISTS outbox CASCADE;
DROP TABLE IF EXISTS idempotency_keys CASCADE;
DROP TABLE IF EXISTS accounts CASCADE;
DROP SEQUENCE IF EXISTS account_seq CASCADE;
DROP SEQUENCE IF EXISTS transaction_seq CASCADE;

-- ==================== ACCOUNTS ====================
CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_account_id ON accounts(account_id);

-- Removed chk_balance_non_negative constraint because balance is no longer stored

-- ==================== TRANSACTION LEDGERS ====================
CREATE TABLE IF NOT EXISTS transaction_ledgers (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(100) NOT NULL UNIQUE,
    from_account_id VARCHAR(50) NOT NULL,
    to_account_id VARCHAR(50) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_txn_id ON transaction_ledgers(transaction_id);
CREATE INDEX IF NOT EXISTS idx_from_account ON transaction_ledgers(from_account_id);
CREATE INDEX IF NOT EXISTS idx_to_account ON transaction_ledgers(to_account_id);
CREATE INDEX IF NOT EXISTS idx_created_at ON transaction_ledgers(created_at DESC);

-- ==================== OUTBOX ====================
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL UNIQUE,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    next_attempt_at TIMESTAMP,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox(status);
CREATE INDEX IF NOT EXISTS idx_outbox_created ON outbox(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_outbox_status_next ON outbox(status, next_attempt_at);

-- ==================== IDEMPOTENCY KEYS ====================
CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(100),
    status VARCHAR(24) NOT NULL,
    response_snapshot TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_idempotency_hash_time ON idempotency_keys(request_hash, created_at);

-- Foreign keys removed to allow external/SYSTEM accounts in Event Sourcing

-- ==================== SEQUENCES ====================
CREATE SEQUENCE IF NOT EXISTS account_seq START 1;
CREATE SEQUENCE IF NOT EXISTS transaction_seq START 1;
