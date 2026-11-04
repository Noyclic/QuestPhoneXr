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
app_test_exit=0
gradle --no-daemon :app:connectedDebugAndroidTest || app_test_exit=$?
test_exit="$app_test_exit"

# connectedDebugAndroidTest may uninstall the target APK after its test run.
# Reinstall QuestPhoneXR, then run the official Khronos loader from an
# independent package with its own native loader binary.
client_test_exit=0
if [[ -s app/build/outputs/apk/debug/app-debug.apk ]]; then
  if ! adb install -r app/build/outputs/apk/debug/app-debug.apk; then
    echo "Could not reinstall QuestPhoneXR before the external client probe."
    client_test_exit=1
  else
    gradle --no-daemon :runtime-client-probe:connectedDebugAndroidTest || client_test_exit=$?
  fi
else
  echo "QuestPhoneXR debug APK is missing; external client discovery cannot run."
  client_test_exit=1
fi
if [[ "$test_exit" -eq 0 && "$client_test_exit" -ne 0 ]]; then
  test_exit="$client_test_exit"
fi

# Keep logcat before the emulator action shuts the device down.
adb logcat -b all -d -v threadtime > ci-results/emulator-logcat.txt || true
grep -Ei 'endxr|openxr|vulkan|OpenGLRenderer|FATAL EXCEPTION|Fatal signal|DEBUG   :' ci-results/emulator-logcat.txt > ci-results/endxr-native-filtered.log || true

# Instrumentation exports files through MediaStore to shared Downloads before
# Gradle removes the target and test APKs. This survives package uninstall on API 35.
adb pull /sdcard/Download/QuestPhoneXR-CI/. ci-results/device-artifacts/ || true
if compgen -G "ci-results/device-artifacts/*.png" > /dev/null; then
  cp ci-results/device-artifacts/*.png ci-results/screenshots/
fi
# MediaStore may append the MIME-derived .txt extension to a .log display name.
if [[ -s ci-results/device-artifacts/endxr-openxr-latest.log ]]; then
  cp ci-results/device-artifacts/endxr-openxr-latest.log ci-results/diagnostics/endxr-openxr-latest.log
elif [[ -s ci-results/device-artifacts/endxr-openxr-latest.log.txt ]]; then
  cp ci-results/device-artifacts/endxr-openxr-latest.log.txt ci-results/diagnostics/endxr-openxr-latest.log
fi
for report in runtime-broker-probe.txt external-client-probe.txt openxr-loader-client-probe.txt; do
  if [[ -s "ci-results/device-artifacts/$report" ]]; then
    cp "ci-results/device-artifacts/$report" ci-results/diagnostics/
  fi
done

# Keep an independent final emulator screenshot even if a UI test failed.
adb exec-out screencap -p > ci-results/screenshots/emulator-final.png || true

if [[ -d app/build/reports/androidTests/connected ]]; then
  cp -R app/build/reports/androidTests/connected ci-results/
fi
if [[ -d app/build/outputs/androidTest-results/connected ]]; then
  cp -R app/build/outputs/androidTest-results/connected ci-results/
fi
if [[ -d runtime-client-probe/build/reports/androidTests/connected ]]; then
  cp -R runtime-client-probe/build/reports/androidTests/connected ci-results/
fi
if [[ -d runtime-client-probe/build/outputs/androidTest-results/connected ]]; then
  cp -R runtime-client-probe/build/outputs/androidTest-results/connected ci-results/
fi

if [[ "$test_exit" -eq 0 ]]; then
  test -s ci-results/diagnostics/endxr-openxr-latest.log || {
    echo "Instrumentation tests passed but the exported OpenXR diagnostic report is missing."
    test_exit=1
  }
  test -s ci-results/diagnostics/runtime-broker-probe.txt || {
    echo "Instrumentation tests passed but the RuntimeBroker discovery report is missing."
    test_exit=1
  }
  test -s ci-results/diagnostics/external-client-probe.txt || {
    echo "Instrumentation tests passed but the separate-package service report is missing."
    test_exit=1
  }
  test -s ci-results/diagnostics/openxr-loader-client-probe.txt || {
    echo "Instrumentation tests passed but the official Khronos loader report is missing."
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
  test -s ci-results/screenshots/runtime-readiness.png || {
    echo "Instrumentation tests passed but the EndXR readiness screenshot is missing."
    test_exit=1
  }
fi

exit "$test_exit"
