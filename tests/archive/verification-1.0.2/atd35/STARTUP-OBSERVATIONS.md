# API 35 ATD first-start observations (not functional test results)

Official AOSP ATD API 35 x86_64 r1, emulator 37.2.12, software TCG, one guest CPU; no hardware virtualization. Full boot reached sys.boot_completed=1 after roughly 8 minutes. Host has available memory; Android system services also experienced startup ANRs.

Both frozen 1.0.1 APKs installed with `Success`. Its first instrumentation attempt terminated `shortMsg=Process crashed` before any JUnit method started. Logcat identifies process-start ANR, then ActivityManager SIGKILL; DropBox main-thread stack is `libz.adler32 → DexFileVerifier.CheckHeader → DexFileLoader.OpenFromZipEntry`. This is not an observed AudioEngine assertion or codec crash.

Frozen round1 1.0.2 main/test APKs installed with `Success`. First short instrumentation similarly terminated before any test started; main-thread stack is `libz.inflate → ZipEntry.ExtractToMemory → DexFileLoader.OpenFromZipEntry`. First explicit long attempt also ended startup-only crash. Neither run is counted as passing, failing a functional assertion, or covering the matrix.

Evidence: baseline-and-round1-start-logcat.txt; app-anr-dropbox.txt; ../round1/atd35/short-tests.txt, short-logcat.txt, long-test.txt. App and test snapshot hashes are in ../round1/apks/SHA256SUMS. Long fixture was copied and device/host SHA-256 match exactly (88995d0efa06ebf869d0be6bb54322f05f3c95d2f7faa18f760a2a88fede5a13).

Recovery is environment-only official package AOT compilation, serial commands `adb shell cmd package compile -m speed -f dev.local.audio` and equivalent test package, followed by unchanged tests/assertions. Results will be kept separately under ../round1/atd35-retry-aot. No app source modification or skipped assertions.
