# Actual 1.0.1 → 1.0.2 causal comparison on the same API 35 device

Frozen 1.0.1 main/test APKs were installed successfully after final-version testing. Official package AOT compilation was applied to eliminate the prior software-emulator cold-start ANR. The original two AAC tests then genuinely executed, in 3.03 seconds:

- aacRemuxPreservesEveryPacket: failed with `IllegalStateException: 音轨没有可读取的音频数据`, AudioEngine.remux line130.
- aacDecodesToValidWav: failed with the same message, AudioEngine.decodeWav line248.

These are expected old-version failures, not failures of final 1.0.2. The same methods passed with the frozen final 1.0.2 APK, together with all 37 API35 tests.

The actual fixture read from old-app private cache hashes identically to the fixture used in final tests: 9bd1554e82c5555119f22477493051679f9a0b33242fb81228e18b860ff2b99e. Independent framework probe of those exact bytes reports first sample time=-23219us, track=1, size/read=258, flags=1; 88 valid packets / 17606 bytes. Actual EOF has track=-1/read=-1. This directly reproduces the known bug using synthetic media; it does not claim inspection of the user's private video.

Raw APK hashes, package metadata, original instrument output, logcat and exact-fixture probe are in this directory. Emulator stopped normally afterward; Android28 validation uses only final 1.0.2.
