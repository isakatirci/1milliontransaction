-- schema.sql — MVP Ledger Service
-- All statements are idempotent (safe to re-run)

-- ==================== ACCOUNTS ====================
CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(50) NOT NULL UNIQUE,
    balance DECIMAL(18, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_account_id ON accounts(account_id);

-- Add non-negative balance constraint (prevents overdraft at DB level)
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS chk_balance_non_negative;
ALTER TABLE accounts ADD CONSTRAINT chk_balance_non_negative CHECK (balance >= 0);

-- ==================== TRANSACTION LEDGERS ====================
CREATE TABLE IF NOT EXISTS transaction_ledgers (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(100) NOT NULL UNIQUE,
    from_account_id VARCHAR(50) NOT NULL,
    to_account_id VARCHAR(50) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
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

-- ==================== FOREIGN KEYS ====================
ALTER TABLE transaction_ledgers DROP CONSTRAINT IF EXISTS fk_from_account;
ALTER TABLE transaction_ledgers ADD CONSTRAINT fk_from_account FOREIGN KEY (from_account_id) REFERENCES accounts(account_id) ON DELETE RESTRICT;

ALTER TABLE transaction_ledgers DROP CONSTRAINT IF EXISTS fk_to_account;
ALTER TABLE transaction_ledgers ADD CONSTRAINT fk_to_account FOREIGN KEY (to_account_id) REFERENCES accounts(account_id) ON DELETE RESTRICT;

-- ==================== SEQUENCES ====================
CREATE SEQUENCE IF NOT EXISTS account_seq START 1;
CREATE SEQUENCE IF NOT EXISTS transaction_seq START 1;

-- ==================== URL SHORTENER ====================
CREATE TABLE IF NOT EXISTS urls (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(50) NOT NULL UNIQUE,
    original_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_urls_short_code ON urls(short_code);
