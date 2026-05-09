-- schema.sql
CREATE TABLE IF NOT EXISTS accounts (
                                        id BIGSERIAL PRIMARY KEY,
                                        account_id VARCHAR(50) NOT NULL UNIQUE,
    balance DECIMAL(18, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
    );

CREATE INDEX idx_account_id ON accounts(account_id);

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

CREATE INDEX idx_txn_id ON transaction_ledgers(transaction_id);
CREATE INDEX idx_from_account ON transaction_ledgers(from_account_id);
CREATE INDEX idx_to_account ON transaction_ledgers(to_account_id);
CREATE INDEX idx_created_at ON transaction_ledgers(created_at DESC);

CREATE TABLE IF NOT EXISTS outbox (
                                      id BIGSERIAL PRIMARY KEY,
                                      event_id VARCHAR(100) NOT NULL UNIQUE,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP
    );

CREATE INDEX idx_outbox_status ON outbox(status);
CREATE INDEX idx_outbox_created ON outbox(created_at DESC);

-- Add foreign key constraints
ALTER TABLE transaction_ledgers
    ADD CONSTRAINT fk_from_account FOREIGN KEY (from_account_id)
        REFERENCES accounts(account_id) ON DELETE RESTRICT;

ALTER TABLE transaction_ledgers
    ADD CONSTRAINT fk_to_account FOREIGN KEY (to_account_id)
        REFERENCES accounts(account_id) ON DELETE RESTRICT;

-- Create sequence for bulk operations
CREATE SEQUENCE IF NOT EXISTS account_seq START 1;
CREATE SEQUENCE IF NOT EXISTS transaction_seq START 1;
