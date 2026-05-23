# Apache JMeter ile Yük Testi Kılavuzu (Başlangıç Seviyesi)

Bu kılavuz, hayatında hiç **JMeter** kullanmamış birinin bile sıfırdan kurulum yapıp, test senaryolarını anlamasını, GUI ve CLI modlarında testleri koşturmasını ve sonuçları raporlamasını sağlamak amacıyla hazırlanmıştır.

---

## 📖 1. Apache JMeter Nedir?

Apache JMeter, uygulamaların performansını ölçmek, yük altındaki davranışlarını test etmek ve stres testleri gerçekleştirmek için kullanılan, saf Java ile yazılmış açık kaynaklı bir araçtır. 

Biz bu projede JMeter'ı; kısa kod çözme (`resolve`) endpoint'imizi (`GET /{shortCode}`) test etmek, **L1/L2 Önbellek (Caffeine & Redis)** verimliliğini ölçmek ve **RequestCoalescer (Thundering Herd engelleme)** yapısının yük altındaki başarısını kanıtlamak için kullanıyoruz.

---

## 🛠️ 2. Sıfırdan Kurulum Adımları

JMeter, Java tabanlı bir araç olduğu için bilgisayarınızda **Java (JRE veya JDK)** kurulu olmalıdır.

### Adım 2.1: Java Kontrolü
Terminalinizi (PowerShell veya CMD) açın ve şu komutu yazın:
```powershell
java -version
```
Eğer ekranda `openjdk version "21..."` veya `java version "17..."` gibi bir sürüm bilgisi görüyorsanız Java hazırdır. Eğer Java yüklü değilse, [Eclipse Adoptium](https://adoptium.net/) adresinden JDK 21 indirip kurun.

### Adım 2.2: JMeter Kurulumu (İki Yöntem)

#### Yöntem A: Otomatik Yerel Kurulum (Tavsiye Edilen)
Projenin ana dizininde sizin için hazırladığım Powershell kurulum betiğini çalıştırın. Bu betik Apache JMeter 5.6.3'ü resmi sunuculardan çeker, yerel klasöre çıkartır ve kullanıma hazır hale getirir (Yönetici yetkisi gerektirmez!):
```powershell
powershell -File jmeter\jmeter_setup.ps1
```
*   **Kurulum Yolu**: `C:\Users\isa\.tools\apache-jmeter-5.6.3`

#### Yöntem B: Manuel Kurulum
1.  [JMeter İndirme Sayfası](https://jmeter.apache.org/download_jmeter.cgi) adresine gidin.
2.  **Binaries** başlığı altındaki `.zip` dosyasını (Örn: `apache-jmeter-5.6.3.zip`) indirin.
3.  İndirdiğiniz dosyayı bilgisayarınızda kalıcı bir klasöre çıkartın (Örn: `C:\tools\jmeter`).

---

## 📂 3. Projedeki Yük Testi Yapısı

Projenin `jmeter/` klasörü altında sizin için üç temel bileşen hazırladık:
*   `jmeter_setup.ps1`: JMeter'ı indiren ve kuran Powershell betiği.
*   `UrlResolveTestPlan.jmx`: Yük testi senaryolarını tanımlayan JMeter Test Planı (XML formatında).
*   `run_load_tests.ps1`: Testleri koşturan, portları otomatik algılayan ve interaktif HTML rapor üreten Powershell betiği.

### 🧪 Test Senaryoları (JMX Dosyasının Yapısı)
Test planımız üç farklı kullanıcı davranışını aynı anda simüle eder:

1.  **Cache Hit Scenario (100 Eşzamanlı Kullanıcı)**:
    *   **Amacı**: Sistem önbelleğinin (L1 Caffeine + L2 Redis) maksimum hızını ve kapasitesini ölçmek.
    *   **Nasıl Çalışır**: Veritabanında önceden hazır olan ülke kısa kodlarını (`TR`, `US`, `DE`, `FR` vb.) rastgele seçerek sürekli sorgular. Önbellek sayesinde bu istekler veritabanına gitmez, doğrudan bellekten döner. Çok yüksek throughput (istek/sn) ve < 5ms gecikme beklenir.
2.  **Cache Miss / DB Lookup (50 Eşzamanlı Kullanıcı)**:
    *   **Amacı**: Önbellekte olmayan ve veritabanında bulunmayan isteklerin veritabanı üzerindeki yükünü ölçmek.
    *   **Nasıl Çalışır**: Her istekte benzersiz bir UUID ekleyerek (`/MISS_abc123...`) istek atar. Önbellekte olmayan bu istekler veritabanını tarar, 404 yanıtı alır. Veritabanının disk ve işlemci yükünü test eder.
3.  **Request Coalescing / Thundering Herd (50 Eşzamanlı Kullanıcı)**:
    *   **Amacı**: Aynı anda (aynı milisaniyede) önbellekte olmayan tek bir kısa koda hücum edildiğinde sistemin kendini koruyabilmesini doğrulamak.
    *   **Nasıl Çalışır**: 50 kullanıcıyı **SyncTimer (Rendezvous Point)** ile baraj gibi tutar, hepsi hazır olduğunda aynı milisaniyede `/COALESCE_run_...` adresine saldırtır. `RequestCoalescer` sayesinde bu 50 istekten sadece **1 tanesi** veritabanına sorgu atar, diğer 49'u bu sorgunun sonucunu bekler ve tek sorgu üzerinden yanıt alır.

---

## 🚀 4. Yük Testlerini Çalıştırma

### Yöntem 1: Komut Satırından Çalıştırma (CLI - Tavsiye Edilen)
Yük testleri yapılırken JMeter'ın arayüzünü (GUI) açmak bilgisayarın işlemcisini yorar ve yanlış performans ölçümlerine yol açar. Bu yüzden gerçek yük testleri **CLI (Non-GUI)** modda yapılır.

1.  Uygulamanın Docker altyapısının (`docker-compose-local.yml`) ve Spring Boot uygulamasının çalışır durumda olduğundan emin olun.
2.  Proje ana dizininde şu PowerShell komutunu çalıştırın:
    ```powershell
    powershell -File jmeter\run_load_tests.ps1
    ```
3.  **Betik şunları otomatik yapar**:
    *   Spring Boot portunu algılar (Port 80 Nginx veya Port 8080 Spring Boot).
    *   Eski test kalıntılarını temizler.
    *   Testi CLI modda başlatır ve 60 saniye boyunca yük uygular.
    *   İşlem bitince CSV loglarını parse ederek başarı/ortalama gecikme metriklerini ekrana basar.
    *   Görsel HTML Dashboard Raporu üretir.

---

### Yöntem 2: Arayüz (GUI) ile Çalıştırma (Görsel Mod)
Eğer testleri görsel olarak izlemek, istek detaylarını canlı görmek veya senaryoyu düzenlemek isterseniz JMeter Arayüzünü kullanabilirsiniz.

#### 1. JMeter Arayüzünü Başlatma:
*   **Powershell (Kurulum Betiği Kullandıysanız)**:
    ```powershell
    & C:\Users\isa\.tools\apache-jmeter-5.6.3\bin\jmeter.bat
    ```
*   **Manuel Kurulum Yaptıysanız**:
    Kurulum klasöründeki `bin/jmeter.bat` (Windows için) dosyasına çift tıklayarak çalıştırın.

#### 2. Test Planını Yükleme:
1.  JMeter açıldığında sol üstteki klasör ikonuna tıklayın veya **File -> Open** yolunu izleyin.
2.  Proje klasörünüz altındaki `jmeter\UrlResolveTestPlan.jmx` dosyasını seçip açın.

#### 3. Canlı İzleme Bileşenleri (Listeners) Ekleme (Opsiyonel):
Canlı sonuçları görmek için test planına dinleyiciler ekleyebilirsiniz:
1.  Sol ağaçta `Url Resolve Test Plan` kök düğümüne sağ tıklayın.
2.  **Add -> Listener -> View Results Tree** (İsteklerin detaylarını, HTTP başlıklarını ve gövdesini tek tek görmek için).
3.  **Add -> Listener -> Summary Report** (İstatistiksel metrikleri, ortalama süreleri ve hata oranlarını canlı tablo halinde görmek için).

> [!WARNING]
> GUI modda test çalıştırırken Listeners (özellikle View Results Tree) bilgisayar belleğini aşırı tüketir. GUI modunu sadece testlerin çalıştığını **doğrulamak** için kullanın. Gerçek yük testlerini her zaman **Yöntem 1 (CLI)** ile yapın.

#### 4. Testi GUI'de Başlatma:
1.  Üst menüdeki **Yeşil Oynat (Start)** butonuna tıklayın.
2.  Test başlar. Sol ağaçta eklediğiniz `View Results Tree` veya `Summary Report` üzerine tıklayarak akan istekleri ve yanıt kodlarını (301, 404) canlı canlı izleyebilirsiniz.
3.  Testi durdurmak isterseniz üstteki **Kırmızı Stop** butonuna basabilirsiniz.

---

## 📈 5. HTML Dashboard Raporunu İnceleme

Komut satırı üzerinden çalıştırılan testlerin sonunda üretilen HTML Dashboard, profesyonel bir performans analiz aracıdır.

1.  Şu dosyayı tarayıcınızda açın: **[jmeter/report/index.html](file:///c:/Users/isa/MVP/jmeter/report/index.html)**
2.  **Açılan ekranda şunları analiz edebilirsiniz**:
    *   **Dashboard -> APDEX (Application Performance Index)**: Kullanıcı memnuniyet oranını renk skalasıyla (Yeşil = Mükemmel) gösterir.
    *   **Charts -> Over Time -> Response Times Over Time**: Yük süresince yanıt sürelerinin nasıl değiştiğini grafik olarak çizer. önbellek ısındıkça (cache warmup) yanıt sürelerinin nasıl düştüğünü buradan görebilirsiniz.
    *   **Charts -> Throughput -> Hits Per Second**: Saniyede sisteme başarılı bir şekilde iletilen istek sayısının grafiğidir.

---

## 🔍 6. RequestCoalescer Doğrulaması Nasıl Yapılır?

Testlerin en önemli çıktılarından biri **RequestCoalescer** doğrulamasıdır. Thundering herd engellemesini loglardan gözlemleyebilirsiniz:

1.  Powershell üzerinden yük testini başlattıktan sonra, Spring Boot uygulamanızın çalıştığı konsol loglarını izleyin.
2.  Milisaniyeler içinde gelen 50 eşzamanlı istek için loglarda **sadece 1 kez** şu satırın yazıldığını göreceksiniz:
    `Resolving shortCode from database: COALESCE_coalesce_run_...`
3.  Hemen altındaki satırlarda ise diğer thread'lerin sıraya girdiğini göreceksiniz:
    `Request coalesced: waiting for inflight request for key: COALESCE_...`
4.  Bu durum, RequestCoalescer mekanizmasının 50 thundering herd isteğini başarıyla tek sorguya indirgediğini ve veritabanını çöküşten koruduğunu ispatlar.

---

## ❓ 7. Sık Karşılaşılan Sorunlar ve Çözümleri

### 1. `java : The term 'java' is not recognized...` Hatası
*   **Nedeni**: Bilgisayarınızda Java yüklü değil veya Ortam Değişkenleri'ne (PATH) eklenmemiş.
*   **Çözüm**: Java kurun ve kurulum betiğini tekrar deneyin.

### 2. `An error occurred: Error in NonGUIDriver...` Hatası
*   **Nedeni**: Bir önceki testten kalan `results.csv` dosyası veya `jmeter/report` klasörü silinmemiş olabilir (JMeter üzerine yazma yapmaz).
*   **Çözüm**: `run_load_tests.ps1` betiği bu temizliği otomatik yapar. Manuel çalıştırma yapıyorsanız `results.csv` dosyasını elinizle silin.

### 3. Test Sonucunda Çok Fazla Kırmızı Hata Görünmesi
*   **Nedeni**: `Cache Miss` veya `Request Coalescing` senaryolarında beklenen `404 Not Found` yanıt kodunun JMeter tarafından varsayılan olarak "Hata" sayılması.
*   **Çözüm**: JMX dosyamıza eklediğimiz `Response Assertion` bileşeni `404` kodunu başarı sayacak şekilde yapılandırılmıştır. JMX üzerinde değişiklik yaparken bu "Ignore Status" ayarlarının korunduğundan emin olun.
