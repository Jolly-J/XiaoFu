# Round 2, API 35 ATD

31 short tests actually executed; 29 passed, 2 failed, no skips. The full run completed (232.215 seconds); it is not a passing suite.

1. **Actual source compatibility defect:** providerNonSeekablePipe raised IOException in MediaExtractor.setDataSource via AudioEngine.open. A valid non-seekable, unknown-length provider descriptor is rejected by the platform extractor. Diagnostic: SOURCE_READ at OPEN_SOURCE, offset 0 / declared length -1. Unknown-length seekable and nonzero-offset known-length descriptors passed.
2. **Test metadata expectation defect:** genuinePlatformHeAacV2 initially expected the extractor's source channels to be 2. Actual HEv2 extractor format is 22050 Hz mono core with profile29; independent real MediaCodec probe expands it to 44100 Hz stereo and decodes complete PCM (1409024 bytes). Decoded WAV assertions must remain stereo/44100; source/core and output formats must be asserted separately. No weakening of decoded-output contract is warranted.

All remaining short tests passed, including original 15, exact valid -1us, negative AAC priming, fragmented MP4, MOV, HEVC, multi-audio, MP3-in-MP4, Opus, genuine HE-AAC, and three seekable provider forms. Raw method status in short-tests.txt. Per-method bounded metadata diagnostics are in diagnostics/compat-diagnostics; no personal media or real API credentials used.

This is the same main APK as round1; the prior long test is tied to that hash only. Round3 changes main source opening and requires a fresh long run.
