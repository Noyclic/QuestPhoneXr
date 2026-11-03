#!/usr/bin/env bash
set -Eeuo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb is not installed or not on PATH." >&2
  exit 1
fi

timestamp="$(date -u +%Y%m%d-%H%M%S)"
out_dir="${1:-questphonexr-monado-diagnostics-$timestamp}"
mkdir -p "$out_dir"

echo "[1/5] Waiting for Android device..."
adb wait-for-device

echo "[2/5] Collecting device information..."
{
  echo "Collected UTC: $(date -u --iso-8601=seconds)"
  echo "ADB version:"
  adb version
  echo
  for prop in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.product.cpu.abi ro.product.cpu.abilist; do
    printf '%s=' "$prop"
    adb shell getprop "$prop" | tr -d '\r'
  done
  echo
  echo "ABIs:"
  adb shell getprop ro.product.cpu.abilist
} > "$out_dir/device-info.txt" 2>&1

echo "[3/5] Collecting logcat (existing logs are preserved; nothing is cleared)..."
adb logcat -b all -d -v threadtime > "$out_dir/logcat-all.txt" 2>&1
grep -Ei 'monado|openxr|xr[A-Z_]|XR_ERROR|RuntimeBroker|org[.]khronos|Binder|FATAL EXCEPTION|Fatal signal|DEBUG[[:space:]]+:' \
  "$out_dir/logcat-all.txt" > "$out_dir/openxr-monado-filtered.log" || true

echo "[4/5] Collecting runtime package and service inventory..."
{
  echo "=== Runtime-related packages ==="
  adb shell pm list packages | grep -i -E 'monado|openxr|khronos' || true
  echo
  echo "=== Monado package ==="
  adb shell dumpsys package org.freedesktop.monado.openxr_runtime.out_of_process 2>&1 || true
  echo
  echo "=== OpenXR Runtime Broker ==="
  adb shell dumpsys package org.khronos.openxr.runtime_broker 2>&1 || true
  echo
  echo "=== Runtime services ==="
  adb shell dumpsys activity services 2>&1 | grep -i -C 5 -E 'monado|openxr|runtime_broker' || true
  echo
  echo "=== QuestPhoneXR package ==="
  adb shell dumpsys package com.noyclic.questphonexr 2>&1 || true
  echo
  echo "=== Running related processes ==="
  adb shell ps -A 2>&1 | grep -i -E 'monado|openxr|questphonexr' || true
} > "$out_dir/openxr-runtime-inventory.txt"

echo "[5/5] Creating archive when zip is available..."
if command -v zip >/dev/null 2>&1; then
  zip -qr "$out_dir.zip" "$out_dir"
  echo "Archive: $out_dir.zip"
else
  echo "zip command not found; diagnostics remain in: $out_dir"
fi

echo "Diagnostics collected in: $out_dir"
echo "Review logcat-all.txt before sharing: Android logs can contain private information."
