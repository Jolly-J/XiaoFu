# Explicit long-file regression: passed

- Snapshot: round1/apks/app.apk (SHA-256 recorded there), API 35 AOSP ATD x86_64.
- Test: CompatibilityMatrixTest#long972sStereo44100, explicitly selected after copying the full fixture.
- Fixture SHA-256 matches on host and target cache: 88995d0efa06ebf869d0be6bb54322f05f3c95d2f7faa18f760a2a88fede5a13.
- Instrumentation: `OK (1 test)`, test STATUS_CODE 0, final INSTRUMENTATION_CODE -1. No assumption skip. Runtime 1417.827 seconds on software emulator.
- M4A: all AAC packet count/hash and timestamp-span assertions passed, expected duration 972 seconds within 0.20 seconds. Output was 16,245,658 bytes before assertion cleanup.
- WAV: 44,100 Hz, stereo, PCM16; PCM data 171,462,656 bytes; measured duration 972.0105215419501 seconds. Header size and full file size assertions passed.
- Periodic process memory: PSS approximately 36–37 MB, no growth proportional to decoded audio size. This is a measured run, not a formal memory ceiling guarantee.
- Final files/exports directory empty: no remaining output or .partial after test cleanup.

Raw evidence: long-test.txt, long-logcat.txt, long-progress.txt, long-memory-progress.txt, exports-after-long.txt. First startup-only attempts and short test-provider crash are separately preserved and not relabeled as success.
