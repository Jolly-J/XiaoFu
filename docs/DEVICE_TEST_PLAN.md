> Historical 1.0.0 preparation/installation record. For the final 1.0.1 results, use ../TEST_REPORT.md and ../verification-1.0.1/instrumentation.txt.

# Device/media test plan

All media fixtures are generated locally with FFmpeg from synthetic color/sine inputs; no user video or third-party copyrighted content is used. The HEVC fixture was verified by ffprobe as HEVC video + AAC audio. The app does not decode the video track for extraction.

## Instrumentation tests

- AAC MP4: inspect metadata, remux, compare each compressed audio packet byte-for-byte, monotonic progress ending at 100%.
- HEVC MP4 + AAC: extract audio without decoding HEVC video.
- Vorbis WebM: device decoder → AAC encoder → M4A; validate codec, sample rate and duration.
- AAC → WAV: real decoder, RIFF/WAVE header and expected PCM byte count.
- Vorbis → WAV: real decoder and PCM output.
- No-audio MP4: explicit actionable rejection.
- Cancellation: cancelled job leaves no partial export.
- MiniMax-format ASR: local HTTP mock inspects actual multipart fields, dummy authorization, WAV payload; timestamp text merges and cached result.
- ASR HTTP 401/429: actionable errors.
- ASR HTTP 500: retry succeeds.
- ASR cancellation: waiting request disconnects and coroutine finishes.
- ASR 601-second synthetic WAV: two overlapping chunks; first succeeds, second fails, retry preserves first and uploads only failed segment; absolute timestamps and completion marker checked.

## Execution

Official Android emulator and API 35 Google APIs x86_64 system image installed. This cloud machine has no `/dev/kvm`, so the emulator uses software CPU emulation and SwiftShader. Status is **pending**, not passed, until `device-test.log` contains instrumentation results.

The ASR tests never call the live MiniMax service and use only a fake key. They prove client HTTP and retry logic, not actual service account availability or live recognition quality.

### First attempt, API 35

The API 35 software-emulated boot did not reach `sys.boot_completed` while the first Gradle build/lint was concurrently running. The cloud workspace became severely resource-constrained. The emulator runner was interrupted successfully (exit 130), and ordinary command responsiveness recovered. No instrumentation test executed, and no screenshot was produced from this attempt. A lower-memory API 28 AOSP image is installed for a serialized diagnostic attempt after the final build; that attempt remains pending.

Additional coverage now includes the official CC BY 3.0 Sintel trailer's AAC packet equality and Android Keystore encryption/replacement/deletion. Total instrumentation test methods: 14 (8 media, 5 ASR, 1 credential storage). `scripts/prepare-test-media.sh` recreates omitted video fixtures for source-archive consumers.

### Final device window, API 28 — 2026-10-25 17:18:30 UTC

The single-core, 768 MiB, 480×800 AOSP API 28 emulator reached `sys.boot_completed=1` (runner printed `BOOT_COMPLETE`; host emulator reported boot duration approximately 154 seconds). `adb install` completed for both the final app APK and its instrumentation APK. The final captured runner output was `Success`, `Performing Streamed Install`, `Success`, followed by `^C`. At 17:18:30 UTC the parent-requested device-test window ended and the runner was interrupted before any instrumentation result. Thus:

- Emulator boot: **passed**.
- APK install: **passed** for final app APK and instrumentation APK.
- Instrumentation execution: **no test results produced / 0 tests verified**.
- App launch, UI screenshot, light/dark rendering, prediction-back behavior: **not verified**.
- Fourteen instrumentation tests remain source-and-compile coverage only, not runtime passes.

The failed/limited device attempt is an environment/timing limitation, not evidence that app installation or tests failed. No actual MiniMax account was used. Re-run `scripts/prepare-test-media.sh` and `./gradlew connectedDebugAndroidTest` on a hardware-accelerated emulator or Android device for runtime verification.
