SubPlay
=======

Telefonuna şarkı indirip internetsiz dinlemeni sağlayan bir Android müzik uygulaması.
Şarkıyı adıyla arayabilir, YouTube ya da Spotify linkini yapıştırabilir veya elindeki
şarkı listesinin tamamını tek seferde indirebilirsin.

Kişisel kullanım için yapılmıştır. YouTube'dan indirme YouTube'un kullanım şartlarına aykırıdır.

KURULUM
-------

1. Bu sayfanın sağındaki Releases bölümüne gir ve en üstteki sürümü aç.
2. subplay.apk dosyasını telefona indir ve aç.
3. Telefon "bilinmeyen uygulama" uyarısı verirse tarayıcına bu izni ver, sonra Yükle'ye bas.

Android 7.0 ve üstü telefonlarda çalışır.

* Güncelleme
Uygulama yeni sürüm çıkınca kendisi haber verir. Çıkan pencerede Güncelle'ye basman,
sonra Android'in kurulum ekranında bir kez daha onaylaman yeterli. Her sürümde neyin
değiştiği Releases sayfasında ve güncelleme penceresinde yazar.

NASIL KULLANILIR?
-----------------

* Şarkı eklemek (sağ alttaki + tuşu)
  - Şarkının adını yazıp Ara'ya basarsın -> YouTube'dan sonuçlar gelir, dokunduğun şarkı iner
  - YouTube linki yapıştırırsın -> Şarkı iner. Çalma listesi linkiyse hepsi iner ve uygulamada aynı adla bir liste açılır
  - Spotify linki yapıştırırsın -> Şarkı, albüm ya da listedeki şarkılar YouTube'da bulunup indirilir
  - Elimde liste var, toplu ekle -> Her satıra bir şarkı yazılmış bir listeyi yapıştırırsın, hepsi sırayla iner

İpuçları
- Toplu eklemede Sanatçı - Şarkı şeklinde yazarsan doğru şarkı daha kolay bulunur. Satır başındaki 1., - gibi işaretleri silmene gerek yok.
- Başka bir uygulamada Paylaş → SubPlay dersen link ya da liste doğrudan uygulamaya gelir.
- Daha önce indirdiğin şarkılar tekrar inmez.
- İndirme sürerken uygulamadan çıkabilirsin; bildirimde ilerlemeyi görürsün.

* Dinlemek
- Bir şarkıya dokun, çalmaya başlar. Ekran kapalıyken ve başka uygulamadayken de çalmaya devam eder.
- Alttaki küçük oynatıcıya dokununca büyük oynatıcı açılır. Aşağı kaydırarak kapatırsın.
- Tümünü çal ve Karışık çal düğmeleri kütüphanenin ve listelerin üstündedir.

* Kütüphane ve listeler
- Arama: üstteki kutuya şarkı ya da sanatçı adı yaz.
- Sıralama: şarkı sayısının yanındaki ⇅ ile isme, sanatçıya ya da eklenme tarihine göre sırala.
- Birden çok şarkı seçmek: bir şarkıya basılı tut, sonra diğerlerine dokun. Üstte sil, listeye ekle ve paylaş düğmeleri çıkar.
- Hızlı kaydırma: sağ kenardaki ince çubuğu sürükle.
- İndirmeden eklemek: şarkı eklerken "Telefona indir" anahtarını kapatırsan şarkı sadece kütüphaneye eklenir, çalınca internetten gelir. İndirilmiş şarkıların yanında renkli ↓ işareti olur.
- Silmek: şarkının ⋮ menüsünde "Telefondan sil" sadece dosyayı siler (şarkı kütüphanede kalır, internetten çalar); "Kütüphaneden kaldır" şarkıyı tamamen kaldırır.
- Liste düzenlemek: listeyi aç, sağ üstteki ✏️'ye bas. Adını değiştirebilir, şarkı ekleyip çıkarabilir, ▲▼ ile sırayı değiştirebilir ve galeriden kapak resmi seçebilirsin.

* Paylaşmak
Paylaş simgesine basınca iki seçenek çıkar:
- Liste olarak paylaş: şarkı adlarını yazı olarak gönderir. Karşı taraf bunu kendi SubPlay'inde Toplu ekle'ye yapıştırırsa aynı şarkılar ona da iner.
- Dosyaları gönder: şarkı dosyalarının kendisini Quick Share, WhatsApp ya da Bluetooth ile gönderir.

* Görünüm
Ayarlar sekmesinden:
- Tema: Koyu, Açık ya da telefonla aynı.
- Renk: 8 hazır renk ya da duvar kağıdının renkleri (Android 12 ve üstü).
- Neon efekti: parlayan kenarlar ve renkli geçişler.

Üçünü istediğin gibi birleştirebilirsin.

* Yedeklemek ve başka telefona taşımak
- Ayarlar → Yedekle: şarkıları, kapakları ve listeleri seçtiğin klasöre kopyalar.
- Ayarlar → Yedekten geri yükle: "SubPlay yedek" klasörünü seçersin, her şey geri gelir.

SIK SORULAN SORULAR
-------------------

YouTube'dan şarkı inmiyor, hata veriyor.
YouTube sık değişir. Ayarlar → İndiriciyi güncelle'ye bas, sonra hata veren indirmede ↻'ye dokun.

Spotify listemin sadece 100 şarkısı geldi.
Spotify giriş yapılmadan en fazla 100 şarkı veriyor. Spotify Premium hesabın varsa
Ayarlar → Spotify bölümünden giriş yap. Yoksa listeyi exportify.net ile
CSV olarak indirip Toplu ekle → Dosyadan aç ile seçebilirsin.

Yanlış şarkı indi.
Şarkının ⋮ menüsünden sil, sonra adını yazıp Ara ile doğrusunu seç.

Şarkılar telefonda nerede duruyor?
Android/data/com.subplay.muzik/files/Music/ klasöründe. Uygulamayı kaldırırsan bu klasör de silinir,
bu yüzden önce Yedekle'yi kullan.

Geliştiriciler için
--------------------

- Kotlin, Jetpack Compose, Media3 (ExoPlayer), yt-dlp (youtubedl-android).
- main dalına her push'ta GitHub Actions APK'yı derler, KEYSTORE_B64 ve KEYSTORE_PASS secret'larıyla imzalar ve
  Releases'a "Sürüm N" olarak yükler. Sürüm notu commit mesajından alınır. Commit mesajına [skip ci] yazılırsa derleme yapılmaz.
- Kod yapısı:
  
  app/src/main/java/com/dogukan/muzik/
    MainActivity.kt, MainViewModel.kt
    data/        MusicRepository (indirme, kütüphane, yedek), SpotifyApi
    download/    DownloadService (arka planda indirme)
    player/      PlaybackService (arka planda çalma)
    update/      Guncelle (uygulama içi güncelleme)
    ui/          ekranlar, oynatıcı, tema, paylaşma, kaydırma çubuğu
