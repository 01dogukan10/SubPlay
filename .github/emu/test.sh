#!/usr/bin/env bash
# Sanal telefonda senaryo: ekran görüntüleri ve kayıtlar emu-sonuc/ klasörüne
set -u
APK="$1"; PKG=com.subplay.muzik; ACT=com.dogukan.muzik.MainActivity
OUT=emu-sonuc; mkdir -p $OUT
n=0
foto() { n=$((n+1)); f=$(printf "%s/%02d_%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; echo "📸 $f"; }
dok() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' > $OUT/ui_son.xml; }
# metni içeren ilk öğeye dokun (metin ya da açıklama)
bas() {
  dok
  local b; b=$(SON="${2:-}" python3 - "$1" <<'PY'
import sys,re
x=open('emu-sonuc/ui_son.xml',encoding='utf-8',errors='ignore').read()
import os
t=sys.argv[1]; bul=[]
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    tx=re.search(r' text="([^"]*)"',n); cd=re.search(r'content-desc="([^"]*)"',n)
    if (tx and t in tx.group(1)) or (cd and t in cd.group(1)):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
        if b:
            x1,y1,x2,y2=map(int,b.groups()); bul.append(((x1+x2)//2,(y1+y2)//2))
if bul:
    p = bul[-1] if os.environ.get("SON") else bul[0]  # SON: en alttaki (ör. mini oynatıcı)
    print(p[0],p[1])
PY
)
  if [ -n "$b" ]; then echo "👆 '$1' -> $b"; adb shell input tap $b; return 0; fi
  echo "❌ '$1' bulunamadı"; return 1
}
bekle() { sleep "$1"; }

adb install -r -g "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c
adb logcat -v time > $OUT/logcat.txt &
LOGPID=$!

# Emülatör açılışta sistem uygulamaları takılabiliyor: biraz bekle ve "yanıt vermiyor" pencerelerini kapat
bekle 25
sistemPenceresi() { dok; grep -q "isn't responding" $OUT/ui_son.xml && { bas "Wait" || bas "Close app"; bekle 2; }; true; }
sistemPenceresi
adb shell am start -n $PKG/$ACT; bekle 8; sistemPenceresi; foto acilis

# Şarkı ekle ve çal (son çalınanlar için 40 sn)
adb shell am start -n $PKG/$ACT -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
bekle 4; sistemPenceresi; bas "İndir" || bas "Ekle"; bekle 70; sistemPenceresi
bas "Never"; bekle 40
# Listeler: son çalınanlar
sistemPenceresi; bas "Listeler"; bekle 3; foto listeler_son_calinanlar
# Genel arama
bas "Kütüphane"; bekle 2; bas "Kütüphanede ara"; adb shell input text "begen"; bekle 3; foto genel_arama
adb shell input keyevent KEYCODE_BACK; bas "Temizle"; bekle 2
# Kaldır + Geri al
bas "Seçenekler"; bekle 2; bas "Kütüphaneden kaldır"; bekle 1; foto kaldirildi_geri_al
bas "Geri al"; bekle 2; foto geri_alindi
# Ayarlar: depolama ve çift şarkılar
bas "Ayarlar"; bekle 2
for k in 1 2; do adb shell input swipe 540 1800 540 700 300; bekle 1; done
foto ayarlar_alt
bas "Depolama"; bekle 4; foto depolama
adb shell input keyevent KEYCODE_BACK; bekle 2
bas "Çift şarkıları bul"; bekle 3; foto ciftler
bas "Tamam" || bas "Vazgeç"; bekle 1
# Beğen sonrası genel arama: "begen" -> Beğendiklerim listesi
adb shell input swipe 450 2000 450 1500 250; bekle 4; bas "Beğen"; bekle 2; adb shell input keyevent KEYCODE_BACK; bekle 2
bas "Kütüphane"; bekle 2; bas "Kütüphanede ara"; adb shell input text "begen"; bekle 3; adb shell input keyevent KEYCODE_BACK; bekle 1; foto genel_arama_liste
bas "Temizle"; bekle 1
# Kısayol: karışık çal
adb shell am start -n $PKG/$ACT -a com.subplay.muzik.KISAYOL -d subplay-kisayol://karisik; bekle 6; foto kisayol_karisik
# Mini oynatıcıyı yukarı kaydır -> büyük oynatıcı (kapak rengi)
adb shell input swipe 450 2000 450 1500 250; bekle 4; foto tam_oynatici_kapak_rengi
bas "Beğen"; bekle 2
adb shell input keyevent KEYCODE_BACK; bekle 2
# Bildirim (♥ ve karıştır düğmeleri)
adb shell cmd statusbar expand-notifications; bekle 3; foto bildirim_dugmeleri
kill $LOGPID 2>/dev/null
grep -E "AndroidRuntime|FATAL|$PKG|SubPlay|yt-dlp|youtubedl" $OUT/logcat.txt | tail -400 > $OUT/logcat_ozet.txt
exit 0
