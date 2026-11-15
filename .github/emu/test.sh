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

adb shell am start -n $PKG/$ACT; bekle 8; foto acilis

# Birkaç şarkı ekle (paylaşımla)
for v in dQw4w9WgXcQ kJQP7kiw5Fk 9bZkp7q19f0 fJ9rUzIMcZQ hT_nvWreIhg; do
  adb shell am start -n $PKG/$ACT -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=$v"
  bekle 4; bas "İndir" || bas "Ekle"; bekle 35
done
bekle 40; foto kutuphane
bas "Ayrıntılar için dokun" && { bekle 2; foto indirme_hatalari; dok; cp $OUT/ui_son.xml $OUT/hatalar_ui.xml; bas "Gizlemek için dokun"; }

# Kaydırma: aşağı inince başlık gizlenmeli, yukarı çıkınca gelmeli
adb shell input swipe 540 1900 540 1100 400; bekle 2; foto asagi_kaydirildi
adb shell input swipe 540 1200 540 1500 400; bekle 2; foto yukari_kaydirildi
adb shell input swipe 540 900 540 2000 300; bekle 2

# Akıllı karıştırma: karışık -> akıllı karışık, sonra çal
bas "Karışık kapalı"; bekle 1; bas "Karışık açık"; bekle 1; foto akilli_secildi
bas "Çal"; bekle 25
adb shell cmd statusbar expand-notifications; bekle 3; foto bildirim
adb shell input keyevent KEYCODE_BACK; bekle 2

# Büyük oynatıcı ve sıra
adb shell input tap 451 2001; bekle 12; foto tam_oynatici
bas "Sıra"; bekle 4; foto sira_ilk
adb shell input keyevent KEYCODE_BACK; bekle 2
for k in 1 2 3 4 5; do bas "Sonraki"; bekle 12; done
foto bes_sarki_sonra
bas "Sıra"; bekle 4; foto sira_sonra
dok; cp $OUT/ui_son.xml $OUT/sira_ui.xml
adb shell input keyevent KEYCODE_BACK; bekle 2

kill $LOGPID 2>/dev/null
grep -E "AndroidRuntime|FATAL|$PKG|SubPlay|yt-dlp|youtubedl" $OUT/logcat.txt | tail -400 > $OUT/logcat_ozet.txt
exit 0
