# MVP Ledger Service (High-Concurrency Edition)

This is a production-ready, high-concurrency Ledger Service MVP built with Spring Boot 3 and Java 21. It is designed to handle thousands of transactions per second with strict financial integrity and real-time observability.

## 🚀 Key Architecture

- **Language & Runtime**: Java 21 (utilizing Virtual Threads for high-concurrency client simulation).
- **Core Framework**: Spring Boot 3.x.
- **Database**: PostgreSQL with Pessimistic Locking (`FOR UPDATE`) for absolute balance integrity.
- **Scaling**: Multi-instance setup (default 4 replicas) behind an Nginx Load Balancer.
- **Asynchronous Engine**: Internal worker pool (10 threads per instance) optimized to match the database connection pool (10), ensuring saturated processing without resource contention.
- **Idempotency**: Full support for `Idempotency-Key` headers to ensure exactly-once semantics.

## 🛠 Tech Stack & Tools

| Component | Technology | Access URL |
| :--- | :--- | :--- |
| **API / LB** | Nginx | `http://localhost` |
| **Stress Test UI** | Thymeleaf + SSE | `http://localhost/stress-test` |
| **Metrics Panel** | Grafana | `http://localhost:3000` (admin/admin) |
| **Metrics Data** | Prometheus | `http://localhost:9090` |
| **LB Stats** | GoAccess | `http://localhost:7890` |
| **DB Admin** | pgAdmin 4 | `http://localhost:5050` (admin@ledger.com/admin) |

## 📊 Observability & Tracing

- **Distributed Tracing**: Every log entry includes `[traceId, spanId]` via Micrometer Tracing. This allows you to track a single transaction across all 4 instances.
- **Real-time Logs**: The Stress Test UI provides a live system log powered by Server-Sent Events (SSE).
- **Metric Collection**: JVM metrics, connection pool stats, and business metrics are automatically scraped by Prometheus.

## 🧪 Running the Stress Test

1. Ensure the project is built: `mvn clean package -DskipTests`
2. Start the stack: `docker-compose up --build`
3. Navigate to `http://localhost/stress-test`
4. Click **"Fire 10,000 Requests"**.

The test will simulate 10,000 bidirectional transfers between two accounts. You can monitor:
- **TPS (Transactions Per Second)** and success rates in the Stress Test UI.
- **Real-time traffic patterns** in GoAccess (`http://localhost:7890`).
- **Resource utilization** in Grafana (`http://localhost:3000`).

## ⚙️ Configuration Highlights

- **Connection Satiation**: We use a `maximum-pool-size: 10` for HikariCP and a matching `10-thread` worker pool. This ensures that every worker always has a dedicated DB connection, eliminating "connection wait" bottlenecks.
- **Asynchronous API**: The `/api/v1/transfer` endpoint is non-blocking (returns `CompletableFuture`), freeing up Tomcat threads immediately to maintain API responsiveness even under 100% load.

## 📦 Deployment

The system is fully containerized. Use the provided `docker-compose.yml` to spin up the entire ecosystem, including the load balancer, 4 service replicas, database, and all monitoring tools.
