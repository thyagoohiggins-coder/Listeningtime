#!/usr/bin/env bash
# Teste de fumaça: instala o APK no emulador, abre o app (com e sem o monitor) e procura falhas.
set -x
PKG=io.github.thyagoohiggins.listeningtime
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK"
adb logcat -c
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n $PKG/.MainActivity
sleep 12
adb exec-out screencap -p > screen-1.png
# liga o monitor (acesso a notificações) e o acesso ao uso, e abre de novo
adb shell cmd notification allow_listener $PKG/$PKG.MediaWatcherService || true
adb shell appops set $PKG GET_USAGE_STATS allow || true
sleep 6
adb shell am force-stop $PKG
# dados de uso de exemplo (alguns apps com tempo, outros sem) — reproduz o cenário real
T=$(date +%F); Y=$(date -d yesterday +%F); M=$(date +%Y-%m)-01
cat > usage.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="t|$T|Netflix" value="3725" />
    <long name="t|$T|YouTube" value="900" />
    <long name="t|$Y|Netflix" value="7300" />
    <long name="t|$Y|Disney+" value="1800" />
    <long name="t|$M|Crunchyroll" value="2400" />
    <long name="t|2025-12-24|HBO Max" value="5000" />
</map>
XML
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/usage.xml'" < usage.xml
adb shell "run-as $PKG cat shared_prefs/usage.xml" | head -3
# mata o processo de novo para o app ler os dados do disco ao abrir
adb shell am force-stop $PKG
sleep 1
adb shell am start -W -n $PKG/.MainActivity
sleep 12
adb shell input swipe 500 1500 500 300 300
sleep 2
adb shell input swipe 500 1500 500 300 300
sleep 4
adb exec-out screencap -p > screen-2.png
adb logcat -d > logcat.txt
echo "=== tela (texto visível) ==="
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -60
echo "================ FALHAS ================"
grep -n -A 40 "FATAL EXCEPTION" logcat.txt || echo "nenhuma FATAL EXCEPTION"
grep -n -B2 -A 25 "ANR in $PKG" logcat.txt || true
if grep -q "FATAL EXCEPTION" logcat.txt; then exit 1; fi
