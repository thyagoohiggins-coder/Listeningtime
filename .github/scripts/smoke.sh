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
adb shell am start -W -n $PKG/.MainActivity
sleep 12
adb shell input swipe 500 1500 500 300 300
sleep 2
adb shell input swipe 500 1500 500 300 300
sleep 4
adb exec-out screencap -p > screen-2.png
adb logcat -d > logcat.txt
echo "================ FALHAS ================"
grep -n -A 40 "FATAL EXCEPTION" logcat.txt || echo "nenhuma FATAL EXCEPTION"
grep -n -B2 -A 25 "ANR in $PKG" logcat.txt || true
if grep -q "FATAL EXCEPTION" logcat.txt; then exit 1; fi
