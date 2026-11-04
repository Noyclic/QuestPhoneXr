#!/usr/bin/env bash
set -Eeuo pipefail

mkdir -p ci-results/screenshots ci-results/diagnostics

adb wait-for-device
api_level="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$api_level" =~ ^[0-9]+$ ]]; then
  echo "Invalid emulator SDK value: '$api_level'"
  adb devices -l
  exit 1
fi
if (( api_level < 35 )); then
  echo "Expected Android 15 (SDK 35+) but emulator reported SDK $api_level."
  adb devices -l
  adb shell getprop ro.build.version.sdk || true
  exit 1
fi

boot_completed="$(adb shell getprop sys.boot_completed | tr -d '\r')"
if [[ "$boot_completed" != "1" ]]; then
  echo "Emulator boot is incomplete (sys.boot_completed='$boot_completed')."
  adb shell getprop dev.bootcomplete || true
  adb devices -l
  exit 1
fi

echo "Emulator ready: Android SDK $api_level"
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

test_exit=0
gradle --no-daemon :app:connectedDebugAndroidTest || test_exit=$?

adb logcat -b all -d -v threadtime > ci-results/emulator-logcat.txt || true
grep -Ei 'endxr|vulkan|OpenGLRenderer|FATAL EXCEPTION|Fatal signal|DEBUG   :' ci-results/emulator-logcat.txt > ci-results/endxr-native-filtered.log || true

adb pull /sdcard/Android/data/com.noyclic.questphonexr/files/ci-screenshots/. ci-results/screenshots/ || true
adb pull /sdcard/Android/data/com.noyclic.questphonexr/files/ci-diagnostics/. ci-results/diagnostics/ || true

if [[ -d app/build/reports/androidTests/connected ]]; then
  cp -R app/build/reports/androidTests/connected ci-results/
fi
if [[ -d app/build/outputs/androidTest-results/connected ]]; then
  cp -R app/build/outputs/androidTest-results/connected ci-results/
fi

exit "$test_exit"
