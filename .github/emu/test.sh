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
# Sanal telefonun ana ekran uygulaması sık sık "yanıt vermiyor" diyor: kapat, hata pencerelerini gizle
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1; bekle 5
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c
adb logcat -v time > $OUT/logcat.txt &
LOGPID=$!

# Emülatör açılışta sistem uygulamaları takılabiliyor: biraz bekle ve "yanıt vermiyor" pencerelerini kapat
bekle 25; sistemPenceresi 2>/dev/null; bekle 40
sistemPenceresi() { dok; grep -q "isn't responding" $OUT/ui_son.xml && { bas "Wait" || bas "Close app"; bekle 2; }; true; }
sistemPenceresi
# Hazır şarkılar: 4 kısa ses dosyası + kütüphane kaydı (YouTube'a gerek kalmadan)
adb shell am start -n $PKG/$ACT; bekle 8; sistemPenceresi
adb shell am force-stop $PKG; bekle 2
mkdir -p /tmp/ses
python3 - <<'PY'
import wave, math, struct
for ad, f in [("A",440),("B",550),("C",660),("D",770)]:
    w = wave.open(f"/tmp/ses/{ad}.wav", "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
    w.writeframes(b"".join(struct.pack("<h", int(8000*math.sin(2*math.pi*f*i/16000))) for i in range(16000*120)))
    w.close()
PY
for ad in A B C D; do adb push /tmp/ses/$ad.wav /sdcard/Android/data/$PKG/files/Music/$ad.wav >/dev/null && echo "itildi $ad"; done
cat > /tmp/ses/library.json <<'JS'
[{"id":"t1","title":"Sarki A","artist":"Sanatci 1","fileName":"A.wav","sourceUrl":"","addedAt":4},
 {"id":"t2","title":"Sarki B","artist":"Sanatci 2","fileName":"B.wav","sourceUrl":"","addedAt":3},
 {"id":"t3","title":"Sarki C","artist":"Sanatci 3","fileName":"C.wav","sourceUrl":"","addedAt":2},
 {"id":"t4","title":"Sarki D","artist":"Sanatci 4","fileName":"D.wav","sourceUrl":"","addedAt":1}]
JS
adb push /tmp/ses/library.json /data/local/tmp/library.json >/dev/null
adb shell "run-as $PKG cp /data/local/tmp/library.json files/library.json" && echo "kütüphane yazıldı"
calan() { adb shell dumpsys media_session | grep -m1 -o "description=[^,]*" | sed 's/description=//'; }
kayit() { echo "$1 -> $(calan)" | tee -a $OUT/gecisler.txt; }

adb shell am start -n $PKG/$ACT; bekle 8; sistemPenceresi; foto kutuphane
bas "Sarki A"; bekle 4; kayit "A'ya dokunuldu"
# Mini oynatıcıdaki Sonraki
bas "Sonraki" son; bekle 3; kayit "mini Sonraki"
# Büyük oynatıcı
bas "Sarki" son; bekle 4; foto tam_oynatici
bas "Sonraki"; bekle 3; kayit "büyük Sonraki"
bas "Önceki"; bekle 1; kayit "büyük Önceki (10 sn içinde, öncekine geçmeli)"
bekle 12; bas "Önceki"; bekle 1; kayit "büyük Önceki (12 sn sonra, başa sarmalı)"
bas "Sonraki"; bekle 1; bas "Sonraki"; bekle 1; bas "Sonraki"; bekle 2; kayit "son şarkıya kadar"
bas "Sonraki"; bekle 2; kayit "son şarkıda Sonraki (başa dönmeli)"
adb shell input keyevent KEYCODE_MEDIA_NEXT; bekle 3; kayit "medya tuşu NEXT"
adb shell input keyevent KEYCODE_MEDIA_PREVIOUS; bekle 3; kayit "medya tuşu PREVIOUS"
# Karışık açıkken
bas "Karışık kapalı"; bekle 2; foto karisik_acik
bas "Sonraki"; bekle 3; kayit "karışık Sonraki 1"
bas "Sonraki"; bekle 3; kayit "karışık Sonraki 2"
bas "Önceki"; bekle 3; kayit "karışık Önceki"
adb shell input keyevent KEYCODE_MEDIA_NEXT; bekle 3; kayit "karışık medya NEXT"
adb shell input keyevent KEYCODE_BACK; bekle 2
# Bildirim
adb shell cmd statusbar expand-notifications; bekle 3; foto bildirim
dok; cp $OUT/ui_son.xml $OUT/bildirim_ui.xml
adb shell dumpsys media_session > $OUT/media_session.txt
kill $LOGPID 2>/dev/null
grep -E "AndroidRuntime|FATAL|$PKG|SubPlay|yt-dlp|youtubedl" $OUT/logcat.txt | tail -400 > $OUT/logcat_ozet.txt
exit 0
