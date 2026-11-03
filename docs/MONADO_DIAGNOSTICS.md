# Monado / OpenXR diagnostics

## CI diagnostics

Every Android CI run uploads the `QuestPhoneXR-CI-diagnostics` artifact, including:

- `emulator-logcat.txt`: all available emulator logcat buffers.
- `openxr-monado-filtered.log`: lines matching Monado, OpenXR, runtime broker, Binder, and crash markers.
- `openxr-runtime-inventory.txt`: installed runtime packages and visible runtime services.
- screenshots and Android instrumentation test reports, when produced.

The API 30 CI emulator does not include Monado by default. A missing runtime package is recorded as an environment fact; it is not, by itself, an application failure. CI can validate app navigation and packaging, but it cannot prove that Monado starts correctly on the Redmi phone.

## Collect diagnostics from a real Android device

1. Enable Developer options and USB debugging on the phone.
2. Connect it to a computer with Android Platform Tools (ADB) installed.
3. Approve the computer's USB debugging prompt on the phone.
4. From the repository root, run:

   ```bash
   bash tools/collect_monado_logs.sh
   ```

   Optionally pass an output directory:

   ```bash
   bash tools/collect_monado_logs.sh ./diagnostics-redmi
   ```

5. Reproduce the OpenXR / Monado problem before running the collector, so the relevant messages are still in the Android log buffers.

The collector does not clear logcat and does not require root. It saves device information, all available logcat buffers, a filtered OpenXR/Monado log, package details, and visible runtime services. It creates a ZIP archive when the `zip` utility is installed.

## Important Android limitation

A normal third-party Android app cannot freely read the complete system logcat on modern Android versions. The ADB collector runs from an authorized computer and can read substantially more diagnostic information than the app itself. Monado's private internal files or inaccessible process memory are not guaranteed to be readable without additional privileges.

## Privacy

Logcat may contain personal information, package names, device identifiers, file paths, or data from other apps. Review `logcat-all.txt` before sharing the archive. Share only the logs needed to diagnose the issue.
