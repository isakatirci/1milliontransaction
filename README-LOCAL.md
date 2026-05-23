# MVP Ledger Service - Yerel Geliştirme Kılavuzu (Hybrid Setup)

Bu kılavuz, **Spring Boot microservice** uygulamasını yerel bilgisayarınızda (IDE'nizde veya terminalde) çalıştırırken, ihtiyaç duyduğu tüm dış servisleri (PostgreSQL, pgAdmin, Prometheus, Grafana, Nginx) Docker üzerinde nasıl koordine edeceğinizi açıklar. 

Bu hibrit (hybrid) çalışma modeli sayesinde:
* Kodunuzda anlık değişiklikler yapabilir, IDE'nizin **Debug** özelliklerini sonuna kadar kullanabilirsiniz.
* Uygulamanız yine de tüm logları Nginx'e gönderir, Prometheus metriklerinizi toplar ve Grafana panelleriniz çalışmaya devam eder.

---

## 🛠️ Yerel Altyapıyı Başlatma (Docker)

Öncelikle, uygulamanın çalışması için gerekli veritabanı ve gözlemlenebilirlik (observability) araçlarını Docker üzerinde arka planda başlatalım.

Proje ana dizininde aşağıdaki komutu çalıştırın:

```powershell
docker compose -f docker-compose-local.yml up -d
```

> [!NOTE]
> Bu komut; PostgreSQL, pgAdmin, Nginx, Prometheus ve Grafana servislerini ayağa kaldırır. Ancak Spring Boot uygulamasını (`ledger-service`) Docker içinde başlatmaz; onu kendi yerelinizde çalıştıracaksınız.

---

## 🚀 Spring Boot Uygulamasını Yerel (Local) Çalıştırma

Spring Boot uygulamanız yerel veritabanına bağlanırken `application.yml` dosyasındaki öntanımlı `postgres` kullanıcısı yerine Docker compose içindeki güvenli kimlik bilgilerini kullanmalıdır.

### ⚠️ Önemli Çevre Değişkenleri (Environment Variables)
Uygulamayı başlatmadan önce aşağıdaki ortam değişkenlerini ayarlamanız **şarttır**:

* `DB_HOST` = `localhost` (Öntanımlı değer)
* `DB_NAME` = `ledger_db`
* `DB_USER` = `ledger_user`
* `DB_PASSWORD` = `ledger_password`

### Yöntem A: Terminal (Maven Wrapper) ile Başlatma

Aşağıdaki PowerShell komutunu kullanarak çevre değişkenleriyle birlikte uygulamayı başlatabilirsiniz:

```powershell
$env:DB_NAME="ledger_db"; $env:DB_USER="ledger_user"; $env:DB_PASSWORD="ledger_password"; .\mvnw.cmd spring-boot:run
```

### Yöntem B: IDE (IntelliJ IDEA, Eclipse veya VS Code) ile Başlatma

1. IDE'nizde `MvpApplication` sınıfını bulun.
2. Sağ tıklayıp **"Edit Run Configuration"** (Çalıştırma Yapılandırmasını Düzenle) seçeneğine gidin.
3. **Environment variables** (Çevre değişkenleri) alanına şu değerleri ekleyin:
   ```env
   DB_NAME=ledger_db;DB_USER=ledger_user;DB_PASSWORD=ledger_password
   ```
4. Uygulamayı **Run** veya **Debug** modunda başlatın.

---

## 🌐 Yerel Erişim Bağlantıları

Altyapı servisleri Docker ağında `host.docker.internal:8080` adresini (yani sizin yerel Spring Boot uygulamanızı) dinleyecek şekilde yapılandırılmıştır.

| Servis / Panel | Erişim Adresi (URL) | Kimlik Bilgileri | Açıklama |
| :--- | :--- | :--- | :--- |
| **Doğrudan Uygulama** | `http://localhost:8080` | Yok | Spring Boot yerel portu |
| **Nginx Proxy / API** | `http://localhost` | Yok | Uygulamanıza Nginx üzerinden erişim sağlar |
| **Ülke Arama Sayfası** | `http://localhost/country-search` | Yok | Arama yapabileceğiniz Thymeleaf arayüzü |
| **Yük Testi Arayüzü** | `http://localhost/stress-test` | Yok | Canlı metrik akışı olan stress testi arayüzü |
| **pgAdmin 4** | `http://localhost:5050` | `admin@ledger.com` / `admin` | PostgreSQL veritabanı yönetim arayüzü |
| **Prometheus** | `http://localhost:9090` | Yok | JVM ve iş metriklerini toplayan veri tabanı |
| **Grafana** | `http://localhost:3000` | `admin` / `admin` | Metrik görselleştirme paneli |
| **GoAccess Stats** | `http://localhost:7890` | Yok | Nginx HTTP isteklerinin canlı analiz ekranı |

---

## 🧪 Sistemin Doğrulanması

1. **Uygulama Sağlık Kontrolü**:
   Tarayıcınızda [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) adresine gidin. Durumun `UP` olduğunu ve veritabanı bağlantısının çalıştığını doğrulayın.
2. **Nginx Yönlendirmesi**:
   [http://localhost/country-search](http://localhost/country-search) sayfasına gidin. Buradaki arama çubuğundan bir ülke (örneğin *Turkey*) aratıp seçin ve **"Search & Redirect"** butonuna basın. Başarıyla Wikipedia sayfasına yönlendirildiğinizi test edin.
3. **Metrik Toplama**:
   [http://localhost:9090/targets](http://localhost:9090/targets) adresine gidin. `ledger-service` hedefinin durumunun **UP** (Yeşil) olduğunu teyit edin.

---

## 🧹 Kapatma

Geliştirmeniz bittiğinde Docker üzerindeki altyapı servislerini durdurmak için şu komutu çalıştırabilirsiniz:

```powershell
docker compose -f docker-compose-local.yml down
```
