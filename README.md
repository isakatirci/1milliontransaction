# /transfer Endpoint'i Uçtan Uca Nasıl Çalışır?

Bu belge, `@PostMapping("/transfer")` endpoint'inin bir hesaptan diğerine nasıl güvenli bir şekilde para transferi yaptığını ve yüksek trafik altında sistemin **tutarlılığını (consistency)** nasıl sağladığını basitçe açıklamaktadır.

## Uçtan Uca Çalışma Akışı (End-to-End Flow)

Bir transfer isteği API'ye ulaştığında sistem sırasıyla şu adımları izler:

1. **İstek Kontrolü (Validation):**
   Kullanıcının kendi kendine para gönderip göndermediği kontrol edilir. Eğer `fromAccountId` ve `toAccountId` aynıysa işlem anında reddedilir.

2. **İstek Özeti Çıkarma (Payload Hashing):**
   Gelen transfer isteğinin içeriği (kimden, kime, ne kadar vb.) kullanılarak eşsiz bir özet (SHA-256 Hash) oluşturulur. Bu özet, isteğin içeriğinin sonradan değişip değişmediğini anlamak için kullanılır.

3. **Veritabanı İşlemi ve Tekrar Deneme (Transaction & Retry Logic):**
   Tüm veritabanı işlemleri tek bir **Transaction** (işlem bütünlüğü) içinde başlatılır. Eğer anlık bir veritabanı kilitlenmesi veya hatası yaşanırsa, sistem işlemi otomatik olarak bekleyerek (Exponential Backoff) 5 defaya kadar tekrar dener.

4. **Tekrarlayan İstek Kontrolü (Idempotency Check):**
   Kullanıcı aynı isteği yanlışlıkla iki kere gönderirse (örneğin butona çift tıklarsa veya ağ koptuğu için tekrar denerse) paranın iki kere çekilmesini önlemek için `Idempotency-Key` header'ı kontrol edilir.
   - Eğer bu anahtar daha önce kullanılmışsa ve içerik (Hash) aynıysa, işlem tekrar yapılmaz. Daha önce veritabanına kaydedilmiş olan başarılı sonuç (Cached Response) direkt geri dönülür.
   - Eğer anahtar aynı ama içerik farklıysa, bu bir çakışma (Conflict) olarak değerlendirilir ve işlem güvenlik amacıyla reddedilir.

5. **Hesapları Kilitleme (Deterministic Locking):**
   Aynı anda binlerce transfer isteği gelebilir. İki hesap arasında aynı anda karşılıklı transfer yapıldığında sistemin kilitlenmesini (Deadlock) önlemek için hesaplar her zaman alfabetik sıraya göre kilitlenir.

6. **Bakiye Kontrolü (Balance Check):**
   Gönderen hesabın güncel bakiyesi kontrol edilir. Yeterli bakiye yoksa işlem iptal edilir (`InsufficientBalanceException`).

7. **Kayıt ve Bakiye Güncelleme:**
   - Transferin detayları `TransactionLedger` tablosuna "COMPLETED" (Tamamlandı) durumuyla kaydedilir.
   - Gönderenin bakiyesinden transfer tutarı düşülür, alıcının bakiyesine bu tutar eklenir ve yeni bakiyeler veritabanına yazılır.

8. **Dış Sistemlere Bildirim (Transactional Outbox Pattern):**
   Transferin başarıyla gerçekleştiğine dair bir mesaj (Event), başka sistemlere (örneğin e-posta veya bildirim servisine) ulaştırılmak üzere `Outbox` tablosuna yazılır.

9. **Sonucu Kaydetme:**
   İşlemin başarılı sonucu, gelecekteki olası tekrar (Retry) isteklerine karşı `IdempotencyKey` tablosuna işlem özetiyle birlikte kaydedilir ve kullanıcıya başarılı yanıt dönülür.

---

## Tutarlılık (Consistency) Nasıl Sağlanıyor?

Finansal sistemlerde hiçbir paranın kaybolmaması veya yoktan var edilmemesi gerekir. Sistemimiz veri bütünlüğünü ve tutarlılığı şu temel mühendislik prensipleriyle garanti altına alır:

* **ACID Transactions (İşlem Bütünlüğü):** Bakiye güncelleme, transfer kaydı, Outbox kaydı ve Idempotency kaydı tek bir veritabanı işlemi (`TransactionTemplate`) içinde yapılır. Eğer bu adımlardan sadece biri bile başarısız olursa, yapılan tüm değişiklikler anında geri alınır (Rollback). Böylece paranın kaybolması veya "havada kalması" imkansız hale gelir.
* **Pessimistic Locking (Kötümser Kilitleme):** İşlem yapılan hesaplar okunurken veritabanı seviyesinde kilitlenir (`FOR UPDATE`). Bu sayede, bir transfer işlemi bitmeden başka bir işlemin aynı hesaba müdahale etmesi (Race Condition) kesin olarak engellenir.
* **Deadlock Prevention (Kilitlenmeyi Önleme):** Hesaplar kilitlenirken her zaman `fromAccountId` ve `toAccountId` değerlerine bakılarak alfabetik sırayla (Deterministic Order) kilitlenir. Bu kural, karşılıklı transferlerde veritabanının sonsuz döngüye girip kilitlenmesini (Deadlock) matematiksel olarak imkansız kılar.
* **Idempotency (Tekrarlanabilirlik):** Sistemin "Exactly-Once" (kesinlikle sadece bir kere) çalışma garantisi vardır. `Idempotency-Key` sayesinde ağ kopması, Timeout (zaman aşımı) veya uygulamanın çökmesi gibi durumlarda istemci aynı isteği güvenle tekrar gönderebilir; aynı işlem asla iki kere gerçekleşmez.
* **Transactional Outbox Pattern:** Veritabanı güncellenirken aynı anda dış sistemlere (örneğin Kafka'ya) mesaj göndermek risklidir (Dual-write problemi). Bunun yerine mesaj, aynı Transaction içinde veritabanındaki `Outbox` tablosuna yazılır. Bu yöntem, veritabanı tutarlılığı ile mesajlaşma (Event-Driven) tutarlılığını %100 senkronize tutar.
