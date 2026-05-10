# 🏦 Ledger Service — Event-Driven Transfer MVP

Bu proje, yüksek hacimli finansal transfer işlemlerini (1 milyona kadar) **Event Sourcing**, **Apache Kafka** ve **WebSocket** teknolojileri kullanarak güvenli, hızlı ve ölçeklenebilir şekilde işleyen bir **ledger (defter) servisidir.**

> **Dil:** Türkçe | **Terminoloji:** İngilizce  
> **Seviye:** Junior Developer'lar için detaylı rehber

---

## 📌 Bu Proje Ne Yapar?

Banka havalesi, kripto transfer veya e-ticaret ödeme sistemlerindeki gibi iki hesap arasında para transferi işlemlerini yönetir. Aşağıdaki temel sorunları çözer:

| Sorun | Çözüm |
|---|---|
| Aynı isteğin iki kere işlenmesi | **Idempotency Key** |
| Milyon işlemde veritabanının kitlenmesi | **Event Sourcing (Append-Only)** |
| Bakiye kayıpları, tutarsızlıklar | **Kafka Event Queue** |
| İşlem sonucunu beklemek için sürekli sorgu atma | **WebSocket (Push Notification)** |

---

## 🏗️ Mimari Genel Bakış

```
┌─────────────────────────────────────────────────────────┐
│                     API LAYER                           │
│            POST /api/v1/transfer                        │
│         (Idempotency-Key header zorunlu)                │
└────────────────────┬────────────────────────────────────┘
                     │  1. İdempotency kontrolü
                     │  2. PENDING kaydı oluştur
                     │  3. Kafka'ya gönder → hemen 202 dön
                     ▼
┌─────────────────────────────────────────────────────────┐
│              KAFKA TOPIC: transfer-requests             │
└────────────────────┬────────────────────────────────────┘
                     │ KafkaListener (Async Worker)
                     │ 1. Bakiyeyi hesapla (SUM of events)
                     │ 2. Yeterliyse → COMPLETED
                     │ 3. Yetersizse → FAILED
                     ▼
        ┌────────────┴─────────────┐
        ▼                          ▼
┌───────────────┐        ┌──────────────────┐
│ transfer-     │        │ transfer-failed   │
│ success topic │        │ topic            │
└───────┬───────┘        └────────┬─────────┘
        └──────────┬──────────────┘
                   ▼
        ┌──────────────────────┐
        │  WebSocket Listener  │
        │  → /topic/transfers  │
        └──────────────────────┘
                   │ Gerçek zamanlı (Real-time) bildirim
                   ▼
        🖥️  Tarayıcıdaki Dashboard (stress-test.html)
```

---

## 🔑 Temel Kavramlar (Junior Developer için)

### 1. Event Sourcing (Olay Kaynağı)
Geleneksel sistemlerde bakiye şöyle tutulur:

```
accounts tablosu: account_id=ACC001, balance=5000  ← bir satır sürekli UPDATE edilir
```

Bu projede ise bakiye **doğrudan saklanmaz.** Bunun yerine tüm transferler birer **event (olay)** olarak deftere yazılır:

```
transaction_ledgers:
  SYSTEM → ACC001  +7000  COMPLETED   (başlangıç bakiyesi)
  ACC001 → ACC002    -5   COMPLETED
  ACC002 → ACC001    +5   COMPLETED
```

Güncel bakiye her zaman şu SQL ile hesaplanır:
```sql
SELECT SUM(CASE WHEN to_account_id = 'ACC001' THEN amount ELSE -amount END)
FROM transaction_ledgers
WHERE (from_account_id = 'ACC001' OR to_account_id = 'ACC001')
AND status = 'COMPLETED'
```

**Avantajı:** Veritabanında hiç `UPDATE` yapılmaz, sadece `INSERT` yapılır. Bu sayede aynı anda gelen binlerce istek birbirini kilitleyemez.

---

### 2. Idempotency Key (Tekrar İşlenmezlik Anahtarı)
Kullanıcı "Transfer yap" butonuna iki kez bastığında veya ağ hatası nedeniyle istek tekrar gönderildiğinde, **aynı transfer iki kez gerçekleşmemelidir.**

Her istekte `Idempotency-Key` adlı benzersiz bir **GUID** (UUID) header olarak gönderilir:

```bash
curl -X POST http://localhost:8080/api/v1/transfer \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId": "ACC001", "toAccountId": "ACC002", "amount": 100}'
```

Aynı `Idempotency-Key` ile ikinci kez istek geldiğinde sistem, **işlemi tekrar yapmadan** önceki sonucu döndürür. Bu anahtarı aynı zamanda işlemin durumunu sorgulamak için kullanabilirsiniz:

```bash
GET /api/v1/transfers/{idempotency-key}
```

---

### 3. Kafka (Mesaj Kuyruğu)
Kafka'yı bir **bant taşıyıcı** gibi düşünün. İstekler doğrudan veritabanına gitmez; önce Kafka'daki kuyruğa girer, ardından arka planda çalışan bir **Consumer (Tüketici)** bu mesajları sırayla işler.

**3 adet Topic (Konu/Kuyruk) vardır:**

| Topic | Açıklama |
|---|---|
| `transfer-requests` | Yeni gelen tüm transfer istekleri buraya düşer |
| `transfer-success` | İşlem başarıyla tamamlandığında bu kuyruğa event yazar |
| `transfer-failed` | Bakiye yetersizse veya hata oluşursa bu kuyruğa event yazar |

---

### 4. WebSocket (Gerçek Zamanlı Bildirim)
Kullanıcı `POST /transfer` isteğini atar ve sistem hemen `202 PENDING` yanıtı döner. Kullanıcı sürekli "Bitti mi?" diye sormak (polling) zorunda kalmaz. Bunun yerine tarayıcı ile sunucu arasında kalıcı bir bağlantı (WebSocket) açılır. İşlem tamamlandığında sunucu, **tarayıcıyı otomatik olarak bilgilendirir.**

---

## 🗂️ Proje Dosya Yapısı

```
MVP/
├── src/main/java/com/isakatirci/MVP/
│   ├── config/
│   │   ├── AppConfig.java               # JdbcTemplate, ObjectMapper konfigürasyonu
│   │   ├── KafkaTopicConfig.java        # 3 Kafka topic tanımı
│   │   └── WebSocketConfig.java        # STOMP WebSocket endpoint konfigürasyonu
│   ├── controller/
│   │   ├── LedgerController.java        # Ana API endpoint'leri
│   │   └── StressTestController.java   # Stress test sayfası ve tetikleyicisi
│   ├── dto/                             # Request/Response nesneleri
│   │   ├── CreateTransferRequest.java
│   │   ├── KafkaTransferMessage.java    # Kafka'ya gönderilen mesaj formatı
│   │   └── TransferResponse.java
│   ├── entity/                          # Veritabanı tabloları
│   │   ├── Account.java                 # Hesap (sadece ID tutar, bakiye tutmaz!)
│   │   ├── TransactionLedger.java       # İşlem defteri (tüm transferler burada)
│   │   ├── IdempotencyKey.java         # Tekrar istek koruması
│   │   └── Outbox.java                  # Outbox pattern kayıtları
│   ├── service/
│   │   └── LedgerService.java           # İş mantığı + Kafka Listener'lar
│   ├── repository/                      # JPA veri erişim katmanı
│   └── exception/                       # Özel hata sınıfları
├── src/main/resources/
│   ├── templates/
│   │   └── stress-test.html            # Thymeleaf + WebSocket Dashboard
│   ├── application.yml                  # Uygulama konfigürasyonu
│   └── schema.sql                       # Veritabanı şeması (her başlatmada sıfırdan kurar)
├── docker-compose.yml                   # Tüm servisler
└── pom.xml                              # Maven bağımlılıkları
```

---

## 🐳 Hızlı Başlangıç (Docker ile — Önerilen Yol)

### Gereksinimler
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) kurulu olmalı
- [JDK 21](https://adoptium.net/) kurulu olmalı
- [Maven](https://maven.apache.org/) veya proje içindeki `mvnw` wrapper kullanılabilir

### Adım 1: Projeyi Build Et
```bash
# Proje kök dizininde (docker-compose.yml'in bulunduğu yerde):
./mvnw clean package -DskipTests
```
> Windows kullanıcıları: `mvnw.cmd clean package -DskipTests`

Bu komut, `target/MVP-0.0.1-SNAPSHOT.jar` dosyasını oluşturur. Docker bu dosyayı container içine kopyalar.

### Adım 2: Docker Compose ile Başlat
```bash
# Eski veritabanı varsa tamamen sil (ilk kurulumda veya şema değişikliğinde):
docker compose down -v

# Tüm servisleri başlat:
docker compose up -d
```

> **Not:** İlk başlatmada Docker, image'ları internetten indireceği için 2-5 dakika sürebilir.

### Adım 3: Servislerin Hazır Olmasını Bekle
```bash
# Logları takip et:
docker compose logs -f ledger-service
```

`Tomcat started on port 8080` mesajını görünce sistem hazırdır.

---

## 🌐 Erişilebilir Adresler

| Servis | Adres | Açıklama |
|---|---|---|
| **Ledger API** | http://localhost:8080 | Ana uygulama |
| **Nginx (Load Balancer)** | http://localhost:80 | Reverse proxy |
| **Stress Test Dashboard** | http://localhost:8080/stress-test | Görsel test arayüzü |
| **Swagger UI (API Docs)** | http://localhost:8080/swagger-ui.html | Tüm endpoint'lerin listesi |
| **pgAdmin (PostgreSQL UI)** | http://localhost:5050 | Veritabanı yönetim paneli |
| **Kafka UI** | http://localhost:8081 | Kafka kuyruk yönetim paneli |
| **Health Check** | http://localhost:8080/api/v1/health | Servis sağlık kontrolü |

### pgAdmin Giriş Bilgileri
- **E-posta:** `admin@ledger.com`
- **Şifre:** `admin`
- **Server eklemek için:** Sağ tık → Register → Server → Host: `postgres`, DB: `ledger_db`, User: `ledger_user`, Pass: `ledger_password`

---

## 📡 API Endpoint Referansı

### Hesap Oluşturma
```bash
POST /api/v1/accounts
Content-Type: application/json

{
  "accountId": "ACC001",
  "initialBalance": 7000.00
}
```

**Yanıt:**
```json
{
  "accountId": "ACC001",
  "balance": 7000.00,
  "timestamp": 1715339400000
}
```

---

### Transfer Başlatma
```bash
POST /api/v1/transfer
Idempotency-Key: <UUID>        ← Her istekte farklı ve benzersiz olmalı!
Content-Type: application/json

{
  "fromAccountId": "ACC001",
  "toAccountId": "ACC002",
  "amount": 100.00,
  "metadata": "fatura ödemesi"
}
```

**Yanıt (202 ACCEPTED — işlem kuyruğa alındı):**
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "PENDING",
  "timestamp": 1715339400000
}
```

---

### Transfer Durumu Sorgulama
```bash
# Idempotency-Key olarak gönderdiğiniz UUID'yi kullanın:
GET /api/v1/transfers/{idempotency-key}
```

**Yanıt:**
```json
{
  "transactionId": "3fa85f64-...",
  "status": "COMPLETED",   ← veya "PENDING" veya "FAILED"
  "timestamp": 1715339401000
}
```

---

### Bakiye Sorgulama
```bash
GET /api/v1/accounts/ACC001/balance
```

**Yanıt:**
```json
{
  "accountId": "ACC001",
  "balance": 6900.00,
  "timestamp": 1715339402000
}
```

---

### Veritabanını Sıfırlama (Sadece Geliştirme/Test için!)
```bash
DELETE /api/v1/debug/reset
```

---

## 🧪 Stress Test Dashboard

`http://localhost:8080/stress-test` adresine gidin. Bu sayfa:

1. **Kaç işlem yapmak istediğinizi** (örn: 100.000) girin
2. **"Start Test"** butonuna basın
3. Arka planda sistem otomatik olarak:
   - Eski verileri siler
   - `ACC001` ve `ACC002` hesaplarını 7.000 TL başlangıç bakiyesiyle oluşturur
   - Belirlediğiniz sayıda birbirini dengeleyen transferi Kafka'ya gönderir
4. **WebSocket bağlantısı** sayesinde her işlemin sonucu gerçek zamanlı olarak ekranda güncellenir — sayaçlar, ilerleme çubuğu (progress bar) ve **TPS (Transaction Per Second)** canlı olarak görünür.

---

## ⚙️ Konfigürasyon Referansı (application.yml)

| Ayar | Varsayılan Değer | Açıklama |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bağlantı adresi |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `ledger_db` | Veritabanı adı |
| `DB_USER` | `postgres` | Kullanıcı adı |
| `DB_PASSWORD` | `postgres` | Şifre |
| `SERVER_PORT` | `8080` | Uygulama portu |
| `hikari.maximum-pool-size` | `50` | Maks. veritabanı bağlantısı |

> Docker ile çalıştırıldığında bu değerler `docker-compose.yml` içindeki `environment` bloğundan otomatik okunur.

---

## 🗄️ Veritabanı Şeması

### `accounts` tablosu
| Kolon | Tip | Açıklama |
|---|---|---|
| `id` | BIGSERIAL | Otomatik artan ID |
| `account_id` | VARCHAR(50) | Benzersiz hesap kodu (ACC001 gibi) |
| `created_at` | TIMESTAMP | Oluşturulma zamanı |

> ⚠️ Dikkat: `balance` (bakiye) kolonu bu tabloda **yoktur!** Bakiye, `transaction_ledgers` tablosundaki event'lerden dinamik olarak hesaplanır (Event Sourcing).

---

### `transaction_ledgers` tablosu (Ana tablo)
| Kolon | Tip | Açıklama |
|---|---|---|
| `transaction_id` | VARCHAR(100) | Benzersiz işlem ID'si |
| `from_account_id` | VARCHAR(50) | Gönderen hesap (SYSTEM olabilir) |
| `to_account_id` | VARCHAR(50) | Alıcı hesap |
| `amount` | DECIMAL(18,2) | İşlem tutarı |
| `status` | VARCHAR(20) | `PENDING`, `COMPLETED`, `FAILED`, `COMPENSATED` |
| `metadata` | TEXT | Serbest açıklama alanı |
| `created_at` | TIMESTAMP | İşlem zamanı |

---

### `idempotency_keys` tablosu
| Kolon | Tip | Açıklama |
|---|---|---|
| `key` | VARCHAR(128) | UUID (istemcinin gönderdiği) |
| `request_hash` | VARCHAR(64) | İsteğin SHA-256 hash'i (içerik değişikliği kontrolü) |
| `transaction_id` | VARCHAR(100) | Bağlı işlem ID'si |
| `status` | VARCHAR(24) | `PENDING`, `COMPLETED`, `FAILED` |
| `expires_at` | TIMESTAMP | 24 saat sonra sona erer |

---

## 🔍 Sık Karşılaşılan Hatalar

### `Invalid or corrupt jarfile /app/app.jar`
**Sebebi:** `docker compose up` çalıştırılmadan önce `mvnw package` yapılmamış, `.jar` dosyası oluşturulmamış.

**Çözüm:**
```bash
./mvnw clean package -DskipTests
docker compose down && docker compose up -d
```

---

### `fk_from_account` Foreign Key hatası
**Sebebi:** Eski `schema.sql`'de `transaction_ledgers` tablosunda Foreign Key kısıtlaması bulunuyordu. `SYSTEM` gibi sanal hesaplar bunu ihlal ediyordu.

**Çözüm:** Bu kısıtlama kaldırıldı. Veritabanını sıfırdan kurmak için:
```bash
docker compose down -v
docker compose up -d
```

---

### `409 Conflict — Data integrity error`
**Sebebi:** Aynı `account_id` ile iki kez hesap oluşturulmaya çalışılıyor. Stress test başlangıcında veritabanı sıfırlanmadan yeni hesaplar ekleniyor.

**Çözüm:** `/api/v1/debug/reset` endpoint'i önce veritabanını temizler. `StressTestController`, testi başlatmadan önce bunu otomatik yapar.

---

### Kafka'ya bağlanamıyor
**Sebebi:** Kafka henüz hazır değilken uygulama ayağa kalkmaya çalışıyor.

**Çözüm:** `docker-compose.yml`'de `ledger-service` için `sleep 20` komutu zaten ekli. Birkaç saniye daha bekleyin.

---

## 🛠️ Geliştirme Ortamı (Docker olmadan, IDE'den)

Önce sadece altyapıyı Docker ile başlatın:
```bash
docker compose up -d postgres zookeeper kafka
```

Ardından IDE'niz üzerinden (IntelliJ, Eclipse, VS Code) `MvpApplication.java` dosyasını çalıştırın veya:
```bash
./mvnw spring-boot:run
```

> `application.yml`'de `KAFKA_BOOTSTRAP_SERVERS` varsayılan değeri `localhost:9092` olduğu için Kafka'ya bağlantı otomatik çalışır.

---

## 📦 Teknoloji Yığını (Tech Stack)

| Teknoloji | Versiyon | Kullanım Amacı |
|---|---|---|
| **Java** | 21 | Uygulama dili |
| **Spring Boot** | 3.5 | Web framework |
| **Spring Kafka** | (Spring Boot ile gelir) | Kafka producer/consumer |
| **Spring WebSocket** | (Spring Boot ile gelir) | Gerçek zamanlı bildirim |
| **Spring Data JPA** | (Spring Boot ile gelir) | Veritabanı ORM |
| **PostgreSQL** | 15 | Ana veritabanı |
| **Apache Kafka** | 7.3 (Confluent) | Mesaj kuyruğu |
| **HikariCP** | 5.1 | Connection Pool |
| **Lombok** | (Spring Boot ile gelir) | Boilerplate azaltma |
| **Thymeleaf** | (Spring Boot ile gelir) | Server-side HTML template |
| **Nginx** | Alpine | Reverse proxy / Load balancer |
| **pgAdmin** | 4 | PostgreSQL yönetim paneli |
| **Kafka UI** | Latest | Kafka yönetim paneli |

---

## 📊 Mimari Kararlar ve Neden Bu Yaklaşım?

### Neden Event Sourcing?
Geleneksel `UPDATE` yaklaşımında milyonlarca eş zamanlı istek geldiğinde veritabanı **satır kilitleri (row-level lock)** nedeniyle tıkanır. Her `SELECT ... FOR UPDATE` ve ardından gelen `UPDATE`, diğer işlemlerin beklemesine neden olur.

Event Sourcing ile **sadece `INSERT`** yapılır. İki işlem aynı tabloya aynı anda `INSERT` yapabilir, birbirini engellemez.

### Neden Kafka?
API katmanı, gelen isteği Kafka'ya bırakıp hemen yanıt döner (ne kadar yüksek trafik olursa olsun). İşlemlerin gerçekte veritabanına yazılması arka planda sabit bir hızda gerçekleşir. Bu sayede **backpressure** (yük geri basıncı) sağlanır ve veritabanı korunur.

### Neden WebSocket?
Transfer işlemi asenkron olduğundan sonuç hemen belli olmaz. İstemcinin sürekli "Bitti mi?" diye sorması (HTTP polling) hem sunucuya ekstra yük bindiri hem de kullanıcı deneyimini kötüleştirir. WebSocket ile sunucu, iş bittiğinde istemciyi **iter (push)**; istemci sormak zorunda kalmaz.

---

*Bu proje, yüksek eşzamanlılık (high-concurrency) gerektiren finansal sistemlerin Event-Driven mimariye geçiş sürecini göstermek amacıyla geliştirilmiştir.*
