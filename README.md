# SubPlay

Telefonuna şarkı indirip **internetsiz** dinlemeni sağlayan bir Android müzik uygulaması.
Şarkıyı adıyla arayabilir, YouTube ya da Spotify linkini yapıştırabilir veya elindeki
şarkı listesinin tamamını tek seferde indirebilirsin.

> Kişisel kullanım için yapılmıştır. YouTube'dan indirme YouTube'un kullanım şartlarına aykırıdır.

---

## Kurulum

1. Bu sayfanın sağındaki **Releases** bölümüne gir ve en üstteki sürümü aç.
2. **subplay.apk** dosyasını telefona indir ve aç.
3. Telefon "bilinmeyen uygulama" uyarısı verirse tarayıcına bu izni ver, sonra **Yükle**'ye bas.

Android 7.0 ve üstü telefonlarda çalışır.

### Güncelleme
Uygulama yeni sürüm çıkınca kendisi haber verir. Çıkan pencerede **Güncelle**'ye basman,
sonra Android'in kurulum ekranında bir kez daha onaylaman yeterli. Her sürümde neyin
değiştiği Releases sayfasında ve güncelleme penceresinde yazar.

---

## Nasıl kullanılır?

### Şarkı eklemek (sağ alttaki + tuşu)
| Ne yaparsan | Ne olur |
|---|---|
| Şarkının adını yazıp **Ara**'ya basarsın | YouTube'dan sonuçlar gelir, dokunduğun şarkı iner |
| **YouTube** linki yapıştırırsın | Şarkı iner. Çalma listesi linkiyse hepsi iner ve uygulamada aynı adla bir liste açılır |
| **Spotify** linki yapıştırırsın | Şarkı, albüm ya da listedeki şarkılar YouTube'da bulunup indirilir |
| **Elimde liste var, toplu ekle** | Her satıra bir şarkı yazılmış bir listeyi yapıştırırsın, hepsi sırayla iner |

**İpuçları**
- Toplu eklemede `Sanatçı - Şarkı` şeklinde yazarsan doğru şarkı daha kolay bulunur. Satır başındaki `1.`, `-` gibi işaretleri silmene gerek yok.
- Başka bir uygulamada **Paylaş → SubPlay** dersen link ya da liste doğrudan uygulamaya gelir.
- Daha önce indirdiğin şarkılar tekrar inmez.
- İndirme sürerken uygulamadan çıkabilirsin; bildirimde ilerlemeyi görürsün.

### Dinlemek
- Bir şarkıya dokun, çalmaya başlar. Ekran kapalıyken ve başka uygulamadayken de çalmaya devam eder.
- Alttaki küçük oynatıcıya dokununca **büyük oynatıcı** açılır. Aşağı kaydırarak kapatırsın.
- **Tümünü çal** ve **Karışık çal** düğmeleri kütüphanenin ve listelerin üstündedir.

- **Şarkı sözleri:** büyük oynatıcıda kontrollerin altındaki kutuda kendiliğinden görünür, şarkıyla birlikte akar. Kutuya dokununca tam ekran açılır; orada satıra dokununca şarkı oraya gider.
- **Uyku zamanlayıcısı:** büyük oynatıcıda ⏾. Belirli bir süre sonra ya da şarkı bitince müzik yavaşça durur.
- **Kaldığı yerden devam:** uygulama kapansa bile çalan liste, şarkı ve saniye hatırlanır; tekrar açınca ▶'ye basman yeterli. Uygulama da en son açık bıraktığın çalma listesinde, sanatçı ya da albüm sayfasında açılır.
- **Kütüphanede kaydırma:** aşağı kaydırınca üstteki arama ve düğmeler gizlenir, şarkılara daha çok yer kalır; biraz yukarı kaydırınca geri gelir.
- **Podcast:** alttaki Podcast sekmesinden adıyla ara (ya da RSS adresini yapıştır) ve abone ol. Her bölüm kaldığın dakikadan devam eder, bitenler "Dinlendi" olarak işaretlenir. Büyük oynatıcıda çalma hızı (0,8x – 2x), 15 sn geri ve 30 sn ileri düğmeleri var. Bölümleri ⬇ ile indirip internetsiz dinleyebilirsin. Yeni bölüm gelince bildirim gelir. Podcast'ler şarkılarınla karışmaz; aboneliklerin yedeğe de girer.
- **Söz senkronu:** sözler şarkıdan önce ya da sonra gidiyorsa tam ekran sözlerde o an söylenen satıra basılı tut ya da −0,5 / +0,5 sn düğmelerini kullan. Ayar o şarkı için hatırlanır.
- **Karışım listeleri (Spotify Blend gibi):** Listeler sayfasındaki ⤲ düğmesi (ya da bir listenin ⋮ → "Başka listeyle karıştır") ile iki ya da daha çok listeyi seç. Listelerden sırayla şarkı alınan yeni bir karışım oluşur; hepsinde olan şarkılar öne gelir. Kaynak listelere şarkı ekleyip çıkardıkça karışım da kendiliğinden güncellenir. Karışımdaki ✏ ile listeleri değiştirebilir, "Düzenle → Normal listeye çevir" ile elle düzenlenebilir hale getirebilirsin.
- **Önce dinle, sonra karar ver:** "+" ile şarkı aradığında sonuçlar artık kendiliğinden inmez. Sonuca dokununca şarkı internetten hemen çalar (telefona inmez, kütüphaneye eklenmez). Beğenirsen yanındaki ➕ ile kütüphaneye ekle ya da ⬇ ile indir. Aynı düğmeler büyük oynatıcıda da "Önizleme" yazısının yanında durur.
- **Her seferinde farklı karışık:** karışık çalmada son zamanlarda dinlediklerin sıranın sonlarına gider; akıllı karıştırma son 5 günde önerdiği şarkıları tekrar önermez.
- **Kolay güncelleme:** güncellemeleri uygulama kendisi kurar; Android 12 ve sonrasında bir kez böyle kurulduktan sonraki güncellemeler izin sormadan kurulur.
- **Kaydırma hareketleri:** küçük oynatıcıyı sola kaydır → sonraki şarkı, sağa → önceki, yukarı → büyük oynatıcı. Büyük oynatıcıda kapağı sola / sağa kaydırarak da şarkı değiştirebilirsin.
- **Bluetooth bağlanınca çal:** Ayarlar → Oynatma ve indirme'den açılır. Kulaklık, hoparlör ya da araba bağlanınca kaldığın yerden çalar.
- **Uygulama simgesi kısayolları:** simgeye basılı tut: "Kaldığın yerden devam", "Karışık çal", "Beğendiklerim".
- **Bildirimde ♥ ve karıştır:** bildirimden ve kilit ekranından beğenebilir, karışık çalmayı açıp kapatabilirsin.
- **Geri al:** kütüphaneden kaldırdığın şarkı, listeden çıkardığın şarkı ya da sildiğin liste için alttaki "Geri al"a dokun.
- **Son çalınanlar:** Listeler sayfasının üstünde son dinlediğin şarkılar ve son açtığın listeler durur. Listeleri ⋮ → "En üste sabitle" ile sabitleyebilirsin.
- **Genel arama:** kütüphane araması çalma listelerini, podcast'leri ve bölümleri de bulur; Türkçe karakter farkı gözetmez ("begen" yazınca "Beğendiklerim" çıkar).
- **Kapağa göre renk:** büyük oynatıcının arka planı çalan şarkının kapağının renginde olur.
- **Depolama ve çift şarkılar:** Ayarlar → Kütüphane'den ne kadar yer kaplandığını görebilir, uzun süredir dinlemediğin indirmeleri silebilir, iki kez eklenmiş şarkıları birleştirebilirsin.
- **Podcast otomatik indirme / silme:** Ayarlar → Podcast'ten açılır.
- **Podcast önerileri:** Podcast sekmesindeki "Senin için" bölümünde ilgini çeken konuları seç (Teknoloji, Komedi, Tarih…). Seçtiklerine, abone olduklarına ve dinlediklerine göre her konunun en sevilen podcast'leri önerilir. Dil olarak Türkçe ya da İngilizce seçebilirsin; kapağa dokununca abone olursun.
- **Beğen (♥):** beğendiğin şarkılar "Beğendiklerim" listesinde toplanır.
- **Yumuşak geçiş:** Ayarlar → Oynatma ve indirme. Şarkılar arasında ses yavaşça kısılıp açılır.

- **Akıllı karıştırma:** 🔀 düğmesine ikinci kez basınca (✦ işareti) o an çalan şarkıya benzeyen, kütüphanende olmayan şarkılar da araya karışır ve dinledikçe yenileri gelir. Bu şarkıların yanında ✦ simgesi olur. Telefona inmezler; çalmadan önce geçici olarak önbelleğe iner, çalınınca silinirler. Beğendiğini ♥ ile kütüphanene indirilmiş olarak eklersin.
- **Ekolayzır ve ses dengeleme:** Ayarlar → Ses. Hazır ayarlar (Pop, Rock, Caz…), bant ayarları, bas güçlendirme; ses dengeleme yüksek ve kısık şarkıları aynı seviyeye getirir.
- **Ana ekran widget'ı:** ana ekrana uzun bas → Widget'lar → SubPlay.
- **Android Auto:** SubPlay arabada medya uygulaması olarak görünür. Play Store dışından kurulduğu için Android Auto ayarlarında geliştirici modunu açıp "Bilinmeyen kaynaklar"a izin vermen gerekir.

### Kütüphane ve listeler
- **Arama:** üstteki kutuya şarkı ya da sanatçı adı yaz.
- **Sıralama:** şarkı sayısının yanındaki ⇅ ile isme, sanatçıya ya da eklenme tarihine göre sırala.
- **Birden çok şarkı seçmek:** bir şarkıya **basılı tut**, sonra diğerlerine dokun. Üstte sil, listeye ekle ve paylaş düğmeleri çıkar.
- **Hızlı kaydırma:** sağ kenardaki ince çubuğu sürükle.
- **İndirmeden eklemek:** şarkı eklerken "Telefona indir" anahtarını kapatırsan şarkı sadece kütüphaneye eklenir, çalınca internetten gelir. İndirilmiş şarkıların yanında renkli ↓ işareti olur.
- **Silmek:** şarkının ⋮ menüsünde "Telefondan sil" sadece dosyayı siler (şarkı kütüphanede kalır, internetten çalar); "Kütüphaneden kaldır" şarkıyı tamamen kaldırır.
- **Sanatçılar ve albümler:** kütüphanenin üstündeki "Sanatçılar" / "Albümler" ile; ya da şarkı menüsünden "Sanatçıya git".
- **Liste eşitleme:** Spotify ya da YouTube linkiyle eklenen listeler kendiliğinden eşitlenir; kaynağa eklenen yeni şarkılar SubPlay'deki listeye de gelir. Listenin üstündeki ↻ ile hemen eşitleyebilirsin.
- **Şarkı adlarını düzelt:** Ayarlar → Kütüphane. Eski şarkılardaki "(Official Video)", "VEVO" gibi ekleri önizlemeyle temizler.
- **Liste düzenlemek:** listeyi aç, sağ üstteki ✏️'ye bas. Adını değiştirebilir, şarkı ekleyip çıkarabilir, ▲▼ ile sırayı değiştirebilir ve galeriden kapak resmi seçebilirsin.

### Akıllı listeler ve istatistikler
- Listeler sekmesinin üstünde **En çok dinlediklerim**, **Son eklenenler** ve **Uzun süredir dinlemediklerin** kendiliğinden güncellenir.
- **İstatistikler:** ne kadar müzik dinlediğin, en çok dinlediğin şarkılar ve sanatçılar, günlük grafik.
- **Sadece Wi-Fi'de indir:** Ayarlar'dan açılırsa mobil veride indirme yapılmaz.

### Paylaşmak
Paylaş simgesine basınca iki seçenek çıkar:
- **Liste olarak paylaş:** şarkı adlarını yazı olarak gönderir. Karşı taraf bunu kendi SubPlay'inde **Toplu ekle**'ye yapıştırırsa aynı şarkılar ona da iner.
- **Dosyaları gönder:** şarkı dosyalarının kendisini Quick Share, WhatsApp ya da Bluetooth ile gönderir.

### Görünüm
**Ayarlar** sekmesinden:
- **Tema:** Koyu, Açık ya da telefonla aynı.
- **Renk:** 8 hazır renk ya da duvar kağıdının renkleri (Android 12 ve üstü).
- **Neon efekti:** parlayan kenarlar ve renkli geçişler.

Üçünü istediğin gibi birleştirebilirsin.

### Yedeklemek ve başka telefona taşımak
- **Ayarlar → Yedekle:** şarkıları, kapakları ve listeleri seçtiğin klasöre kopyalar.
- **Ayarlar → Yedekten geri yükle:** "SubPlay yedek" klasörünü seçersin, her şey geri gelir.

---

## Sık sorulan sorular

**YouTube'dan şarkı inmiyor, hata veriyor.**
YouTube sık değişir. **Ayarlar → İndiriciyi güncelle**'ye bas, sonra hata veren indirmede ↻'ye dokun.

**Spotify listemin sadece 100 şarkısı geldi.**
Spotify giriş yapılmadan en fazla 100 şarkı veriyor. Spotify Premium hesabın varsa
**Ayarlar → Spotify** bölümünden giriş yap. Yoksa listeyi [exportify.net](https://exportify.net) ile
CSV olarak indirip **Toplu ekle → Dosyadan aç** ile seçebilirsin.

**Yanlış şarkı indi.**
Şarkının ⋮ menüsünden sil, sonra adını yazıp **Ara** ile doğrusunu seç.

**Şarkılar telefonda nerede duruyor?**
`Android/data/com.subplay.muzik/files/Music/` klasöründe. Uygulamayı kaldırırsan bu klasör de silinir,
bu yüzden önce **Yedekle**'yi kullan.

---

<details>
<summary>Geliştiriciler için</summary>

- Kotlin, Jetpack Compose, Media3 (ExoPlayer), yt-dlp (youtubedl-android).
- `main` dalına her push'ta GitHub Actions APK'yı derler, `KEYSTORE_B64` ve `KEYSTORE_PASS` secret'larıyla imzalar ve
  Releases'a "Sürüm 1.X.Y" olarak yükler. Sürüm notu commit mesajından alınır. Commit mesajına `[skip ci]` yazılırsa derleme yapılmaz.
- Sürüm numarası `surum.txt` dosyasında: `1.ORTA.SON`. Yeni özellik eklenince ORTA bir artar ve SON sıfırlanır; hata düzeltmesi, görünüm ya da küçük değişikliklerde SON bir artar.
- Kod yapısı:
  ```
  app/src/main/java/com/dogukan/muzik/
    MainActivity.kt, MainViewModel.kt
    data/        MusicRepository (indirme, kütüphane, yedek), SpotifyApi
    download/    DownloadService (arka planda indirme)
    player/      PlaybackService (arka planda çalma)
    update/      Guncelle (uygulama içi güncelleme)
    ui/          ekranlar, oynatıcı, tema, paylaşma, kaydırma çubuğu
  ```
</details>
