SubMüzik
========

Kişisel kullanım için şarkı indirme ve çevrimdışı dinleme uygulaması (Android).
Kotlin + Jetpack Compose + Media3 (ExoPlayer) + yt-dlp ile yazıldı.


NE YAPAR?
---------

Şarkı ekleme (+ tuşu)
  - Şarkı adı yazıp "Ara": YouTube'da arar, 10 sonuç gösterir, dokunduğun iner.
  - YouTube linki: tek video, YouTube Music linki ya da çalma listesi.
    Çalma listesi linkinde aynı adla uygulama içinde bir liste açılır.
  - Spotify linki: şarkı, albüm ya da çalma listesi. Spotify dosyaları
    şifreli olduğu için uygulama şarkı adını ve sanatçıyı Spotify'dan okur,
    aynı şarkıyı YouTube'da bulup indirir.
  - Doğrudan ses dosyası linki (.mp3, .m4a, .ogg, .flac ...).
  - Toplu ekleme: "Elimde liste var, toplu ekle" ile her satırda bir şarkı
    olan listeyi yapıştır ya da .txt / .csv dosyası seç. Her satır aranıp
    indirilir ve istersen tek bir çalma listesine toplanır.
    "Sanatçı - Şarkı" biçimi en iyi sonucu verir. Satır başındaki 1. 2) - •
    gibi işaretler temizlenir. Exportify'dan alınan Spotify CSV'si de okunur.
  - Başka uygulamadan "Paylaş -> SubMüzik": link paylaşılırsa normal ekleme,
    çok satırlı metin paylaşılırsa toplu ekleme açılır.
  - Aynı anda en fazla 2 YouTube indirmesi yapılır, ilerleme kütüphanede görünür.

Kütüphane
  - Ada ya da sanatçıya göre arama, "Tümünü çal".
  - Her şarkıda: listeye ekle, adını/sanatçısını düzenle, sil.
  - Kapak resimleri: YouTube kapağı, Spotify şarkı/albüm kapağı ya da
    dosyanın içindeki kapak.

Çalma listeleri
  - Liste oluştur, sil; şarkı ekle/çıkar; sırayla ya da karışık çal.

Oynatıcı
  - Arka planda çalma; bildirim ve kilit ekranı kontrolleri (kapakla birlikte).
  - Kulaklık çıkınca durur, başka uygulama ses çalınca duraklar.
  - Alttaki mini oynatıcıya dokununca tam ekran oynatıcı açılır
    (büyük kapak, ilerleme çubuğu, karışık, tekrar). Aşağı kaydırarak kapanır.

Görünüm (Ayarlar)
  - Tema: Sistem / Koyu / Açık.
  - Renk: 8 hazır vurgu rengi ya da (Android 12+) duvar kağıdı renkleri.
  - Neon efekti: renkli geçişler, parlayan kenarlar ve kapak ışıması.
  Bu üçü birbirinden bağımsızdır, istediğin gibi karıştırılabilir.

Güncelleme
  - İndirici (yt-dlp) günde bir kez kendini günceller. YouTube indirmeleri
    hata verirse: Ayarlar -> İndiriciyi güncelle.
  - Uygulama güncellemesi: her açılışta ve arka planda saat başı GitHub
    Releases'a bakar. Yeni sürüm varsa pencere/bildirim çıkar, "Güncelle"
    APK'yı indirip Android'in kurulum ekranını açar.
    Repo herkese açık (public) olmalı; token gerekmez.

Diğer
  - Sıralama: eklenme tarihi, isim, sanatçı (baştaki The/A/An sayılmaz),
    listede kendi sırası.
  - Çoklu seçim: şarkıya basılı tut -> sil, listeye ekle, listeden çıkar, paylaş.
  - Listeler: adını değiştir, şarkı ekle/çıkar, sırala, galeriden kapak seç.
  - Hızlı kaydırma çubuğu (sağ kenar).
  - Paylaşma: metin listesi ya da şarkı dosyaları.
  - Spotify girişi: kendi listelerinin tamamı ve beğenilen şarkılar.
  - Yedekle / Yedekten geri yükle: şarkılar, kapaklar ve listeler bir klasöre.


DOSYALAR NEREDE?
----------------

  Şarkılar:  Android/data/com.subplay.muzik/files/Music/
  Kapaklar, kütüphane ve listeler uygulamanın kendi klasöründe tutulur.
  Uygulama kaldırılırsa hepsi silinir.


DERLEME
-------

  main dalına her push'ta GitHub Actions (.github/workflows/build.yml):
    1. APK'yı derler.
    2. KEYSTORE_B64 ve KEYSTORE_PASS secret'larıyla sabit anahtarla imzalar
       (böylece yeni sürüm eskisinin üzerine kurulur).
    3. "Sürüm N" adıyla Releases'a subplay.apk olarak yükler.
  Sürüm kodu = 100 + Actions çalıştırma numarası.
  Commit mesajına [skip ci] yazılırsa derleme yapılmaz.

  İmza anahtarı (subplay-anahtar.p12) ve şifresi kaybolursa yeni sürümler
  eskisinin üzerine kurulamaz; güvenli bir yerde saklanmalı.


KOD YAPISI
----------

  app/src/main/java/com/dogukan/muzik/
    MainActivity.kt          Giriş, paylaşım (Paylaş -> SubMüzik) yakalama
    MainViewModel.kt         Oynatıcı bağlantısı, uygulama güncelleme durumu
    data/MusicRepository.kt  İndirme (doğrudan, YouTube, Spotify, toplu),
                             arama, kütüphane ve listeler (JSON)
    player/PlaybackService.kt  Arka planda çalma (Media3 MediaSessionService)
    update/Guncelle.kt       GitHub Releases'tan güncelleme + saatlik kontrol
    download/DownloadService.kt  İndirme sürerken uygulamayı ayakta tutan servis
    ui/App.kt                Ana ekranlar ve diyaloglar
    ui/Player.kt             Mini ve tam ekran oynatıcı
    ui/Settings.kt           Ayarlar ekranı
    ui/Theme.kt              Tema, renkler, neon efekti, kapak bileşeni


SÜRÜM 9 DÜZELTMELERİ
--------------------

  1. Ekran kapalıyken müzik artık durmuyor (oynatıcı telefonu uyanık tutuyor).
  2. Uygulamadan çıkınca indirmeler yarıda kalmıyor; indirme sürerken
     "N şarkı indiriliyor" bildirimi çıkıyor ve iş bitince kayboluyor.
  3. Kütüphaneden ya da listeden şarkıya dokununca karışık mod artık kapanmıyor.
  4. Kurulmuş güncelleme APK'sı (~43 MB) ve yarım kalan indirme dosyaları
     açılışta siliniyor.
  5. Aynı şarkı inerken tekrar eklenirse ikinci kez inmiyor
     (listesi varsa şarkı bitince oraya da ekleniyor).
  6. Hata veren indirmelerde "Tekrar dene" düğmesi var.
  7. Yatay ekranda tam ekran oynatıcı taşmıyor (kapak solda, kontroller sağda).
  8. Bildirim izni sadece bir kez soruluyor.
  9. Çalan şarkının adı düzenlenince bildirim ve oynatıcı da yeni adı gösteriyor.
 10. Toplu eklemede doğru mesaj: liste okunamadı / hepsi zaten var /
     kaç tanesi indiriliyor.


NOT
---

  YouTube'dan indirme YouTube'un kullanım şartlarına aykırıdır;
  uygulama yalnızca kişisel kullanım için yazılmıştır.
