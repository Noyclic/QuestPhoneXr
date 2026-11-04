#!/usr/bin/env bash
set -Eeuo pipefail

mkdir -p ci-results/screenshots ci-results/diagnostics ci-results/device-artifacts

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

# Keep logcat before the emulator action shuts the device down.
adb logcat -b all -d -v threadtime > ci-results/emulator-logcat.txt || true
grep -Ei 'endxr|vulkan|OpenGLRenderer|FATAL EXCEPTION|Fatal signal|DEBUG   :' ci-results/emulator-logcat.txt > ci-results/endxr-native-filtered.log || true

# Instrumentation exports files through MediaStore to shared Downloads before
# Gradle removes the target and test APKs. This survives package uninstall on API 35.
adb pull /sdcard/Download/QuestPhoneXR-CI/. ci-results/device-artifacts/ || true
if compgen -G "ci-results/device-artifacts/*.png" > /dev/null; then
  cp ci-results/device-artifacts/*.png ci-results/screenshots/
fi
if [[ -s ci-results/device-artifacts/endxr-openxr-latest.log ]]; then
  cp ci-results/device-artifacts/endxr-openxr-latest.log ci-results/diagnostics/
fi

# Keep an independent final emulator screenshot even if a UI test failed.
adb exec-out screencap -p > ci-results/screenshots/emulator-final.png || true

if [[ -d app/build/reports/androidTests/connected ]]; then
  cp -R app/build/reports/androidTests/connected ci-results/
fi
if [[ -d app/build/outputs/androidTest-results/connected ]]; then
  cp -R app/build/outputs/androidTest-results/connected ci-results/
fi

if [[ "$test_exit" -eq 0 ]]; then
  test -s ci-results/diagnostics/endxr-openxr-latest.log || {
    echo "Instrumentation tests passed but the exported OpenXR diagnostic report is missing."
    test_exit=1
  }
  test -s ci-results/screenshots/mode-selection.png || {
    echo "Instrumentation tests passed but the mode-selection screenshot is missing."
    test_exit=1
  }
  test -s ci-results/screenshots/game-library.png || {
    echo "Instrumentation tests passed but the game-library screenshot is missing."
    test_exit=1
  }
  test -s ci-results/screenshots/sandbox-diagnostics-button.png || {
    echo "Instrumentation tests passed but the Sandbox diagnostics screenshot is missing."
    test_exit=1
  }
fi

exit "$test_exit"
