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
bas "Podcast"; bekle 3; foto podcast_ilk
bas "Teknoloji"; bekle 1; bas "Komedi"; bekle 12; foto oneriler_tr
bas "İngilizce"; bekle 12; foto oneriler_en
adb shell input swipe 540 1700 540 900 300; bekle 2; foto oneriler_en_asagi
bas "Tümü"; bekle 3; foto tumu
adb shell input swipe 540 900 540 1900 300; bekle 1; bas "Kapat"; bekle 2
dok; python3 - <<'PY' > $OUT/kart.txt
import re
x=open('emu-sonuc/ui_son.xml',encoding='utf-8',errors='ignore').read()
print("ok")
PY
kill $LOGPID 2>/dev/null
grep -E "AndroidRuntime|FATAL|$PKG|SubPlay|yt-dlp|youtubedl" $OUT/logcat.txt | tail -400 > $OUT/logcat_ozet.txt
exit 0
