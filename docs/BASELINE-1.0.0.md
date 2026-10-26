# 1.0.0 device verification (post-delivery)

Tested unchanged APK SHA256: `d700bb2d813ef801fcf36f51d4d1e5ed97f62d31b6b68d5fc5e84b2ec6415944`.

Environment: official AOSP Android 9/API 28 x86_64 image, emulator 37.2.12, TCG software CPU (no KVM), SwiftShader, one vCPU, 768 MiB guest RAM, 480×800 display. Reused installed app/userdata; no wipe. Emulator boot completed, package paths confirmed for application and test APK.

## Instrumentation result

14 executed; **11 passed, 3 failed**. Runner duration 36.867 seconds. Full trace: `instrumentation.txt`.

Passed:
- Android Keystore encrypt/read/replace/delete; no plaintext key in encrypted blob.
- AAC remux: every compressed packet exactly equal; monotonic progress to 100%.
- Public Sintel AAC remux: every packet equal.
- HEVC-video/AAC-audio container extraction.
- No-audio input rejection.
- Cancelled extraction cleans partial output.
- ASR local HTTP multipart and timestamp merge, delete transcript cache.
- ASR 401/429 actionable errors.
- ASR retry after HTTP 500.
- ASR cancellation while awaiting response.
- ASR 601-second PCM WAV: overlapping two-part plan, failed-second-part retry without re-uploading successful first part.

Failed:
- AAC → WAV.
- Vorbis → WAV.
- Vorbis → AAC/M4A.

All three fail with the incorrect “protected/encrypted track” message at end-of-stream. `AudioEngine.decodeWav` and `transcode` checked encryption flags before checking EOS.

## Independently measured root cause

The standalone `eos-probe/EosProbe.java` was compiled into a tiny Android dex and run with Android `app_process` against both original test fixtures; it does not modify or instrument the app implementation.

For both AAC and Vorbis, the last real packets have flags `1` and encrypted mask `0`. At EOS, sample time, track index, sample size, flags, and `readSampleData` all return `-1`. The expression `flags & SAMPLE_FLAG_ENCRYPTED` then evaluates to `2`, so the old code falsely treats EOF as DRM.

Raw evidence: `eos-probe/aac.txt`, `eos-probe/vorbis.txt`.

M4A→ASR preparation also calls the same WAV decoder and is affected by this defect. Original HTTP tests began with WAV and did not cover that combination. A new regression test covers MP4→M4A→local WAV preparation→mock HTTP response in the forthcoming patch test build.

## UI observation and limits

The home UI rendered, but software-emulator System UI ANR dialogs obscured the initial light/dark screenshots. A subsequent early capture was blank while the app was launching. These are **not clean visual passes**, and no claim of physical-device performance or predictive-back validation is made. Continue UI verification after the corrected build is installed.

No real MiniMax request, real API key, or user media was used. The original delivered APK and ZIP remain unchanged.
