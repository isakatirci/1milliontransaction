# Read Replica Setup & Routing Configuration

This document describes the read-replica database routing mechanism implemented in the MVP Ledger Service.

---

## 1. Overview & Architecture

To improve database read performance and scale database operations, database traffic is split:
- **Write Operations** (Insert, Update, Delete, and non-read-only Transactions) are routed to the **Primary Database** (`postgres`).
- **Read Operations** (Queries, Balance checks, Uptime metrics) are routed to the **Read Replica Database** (`postgres-replica`).

```
                    +------------------------+
                    |   Ledger Nginx LB /    |
                    |     Spring Boot        |
                    +-----------+------------+
                                |
             +------------------+------------------+
             | (Write / Default)                   | (Read-Only)
             v                                     v
+------------+-----------+             +-----------+------------+
|  Primary PostgreSQL    |             |   Replica PostgreSQL   |
|       (5432)           |             |       (5433)           |
+------------+-----------+             +-----------+------------+
             |                                     ^
             |       Streaming Replication         |
             +-------------------------------------+
```

---

## 2. Infrastructure Setup (PostgreSQL Streaming Replication)

We use PostgreSQL physical streaming replication inside Docker.

### Primary Database Configuration (`postgres`)
- Startup script `init-replication.sql` automatically runs inside `docker-entrypoint-initdb.d/` to create a `replicator` user with `REPLICATION` privileges.
- Replicas connect to the primary using this account.

### Replica Database Configuration (`postgres-replica`)
- The replica service starts after the primary is ready.
- It checks if `/var/lib/postgresql/data` is empty. If so, it dynamically clones the primary using `pg_basebackup -R`.
- The `-R` option automatically generates `standby.signal` (putting it in standby/read-only mode) and writes replication parameters into `postgresql.auto.conf`.
- Standard live replication continues automatically from that point onwards.

---

## 3. Spring Boot Database Routing Implementation

The application dynamically chooses the target datasource on a per-transaction level using Spring's `AbstractRoutingDataSource` and `LazyConnectionDataSourceProxy`.

### Components

1. **`DataSourceConfig`**:
   Defines two separate Hikari connection pools (`spring.datasource.writer` and `spring.datasource.reader`). Registers a `RoutingDataSource` wrapping both.
2. **`RoutingDataSource`**:
   Extends `AbstractRoutingDataSource` and overrides `determineCurrentLookupKey()`. It queries Spring's `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` to decide whether to return `READER` or `WRITER`.
3. **`LazyConnectionDataSourceProxy`**:
   Normally, Spring's transaction manager retrieves a database connection *before* initializing the transaction synchronization context. Wrapping our RoutingDataSource in `LazyConnectionDataSourceProxy` defers connection acquisition until the first statement is actually run, ensuring the correct lookup key is selected.

---

## 4. How to Route Queries to Replica

To route a read operation to the replica, decorate the service method or controller endpoint with `@Transactional(readOnly = true)`.

### Example Controller Endpoint:
```java
@GetMapping("/accounts/{accountId}/balance")
@Transactional(readOnly = true)
public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
    // This method will acquire a database connection from the postgres-replica datasource
    return accountRepository.findByAccountId(accountId)
        ...
}
```

### Important Rule:
If a read operation is executed within a read-write transaction (i.e. called from a method with standard `@Transactional`), it will continue using the **Writer** datasource connection to ensure read-after-write consistency.

---

## 5. Local Development & Verification

### Running the Infrastructure Locally

To start the database cluster locally, use the local compose file:
```bash
docker-compose -f docker-compose-local.yml up -d postgres postgres-replica redis
```

The primary database will be exposed on port `5432` and the replica on port `5433`.

### Verifying Routing logs

When the application runs, check the logs for statements like:
```text
Routing database connection: isReadOnly=true, target=READER
```
or
```text
Routing database connection: isReadOnly=false, target=WRITER
```
to ensure that connections are routed correctly.
