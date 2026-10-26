# 1.0.2 diagnosis and regression plan

No user messages, uploads, live ASR, personal media, or edits to frozen 1.0.0/1.0.1 deliverables. Test data is synthesized locally. App main code changes belong to the parent/engine task, not this device runner.

## Confirmed root-cause evidence before patch

On actual Android 15/API 35 MediaExtractor, the unchanged AAC MP4 fixture starts with `sampleTime=-23219, trackIndex=1, size=258, flags=1, read=258`. Direct API 28 probes now confirm all three fixtures start at time 0, while the default fixture carries the previously diagnosed incorrect encoder-delay 45158. Thus the 1.0.1 `sampleTime<0` EOS guard rejects valid AAC priming packets on modern Android.

A second synthetic MP4 uses a 1,000,000-tick audio media timescale and an edit-list media_time of one tick. API 35 exposes first `sampleTime=-1` **with valid track/read data**, while actual EOF has trackIndex=-1 and read=-1. Merely changing `<0` to `==-1` is therefore incorrect. A no-edit-list control starts at zero. All three contain exactly 88 packets and 17606 compressed audio bytes.

Raw sources, generation script, FFprobe output and independent Android dex probe are in `media/`, `probe/`, and `api35/`. No app implementation is involved in the independent probe.

## Matrix

| Dimension | Cases | Required evidence |
|---|---|---|
| Android platform | API 28; API 35 | Actual framework version, native probe, instrumented app results, no inferred cross-version pass |
| Timeline | AAC negative priming; valid exactly -1 microsecond; nonnegative control | First three packets + EOF, sample count/hash preservation, one-time normalization, no empty output |
| Containers/video | MP4/H.264, MP4/HEVC, fragmented MP4, MOV, WebM, multiple audio tracks | Correct selected audio track, valid M4A/WAV output, duration/rate/channels |
| Audio codecs | AAC-LC, MP3 in MP4, Opus/Vorbis WebM; HE/HEv2 only where actual platform encoder/decoder supports | Real encode/decode; unavailable capabilities reported, not counted as passes |
| Duration | 972 seconds, stereo, 44.1 kHz | Full output length/duration and bounded memory/cancellation, not just short-file smoke |
| Document provider | Known length, known nonzero offset, unknown length with zero offset, non-seekable pipe | Valid AFD contract; true content URI read/copy behavior, failure cleanup |
| Safety/error paths | Empty audio, malformed input, cancellation, genuine encrypted flag, stale/denied URI | No false DRM from EOF; clear errors; partial-output cleanup; no external transfer |
| ASR client | Existing local multipart/error/cancel/resume tests + M4A decode preparation | Mock only, no real key/API request |

## Execution order / resource control

One emulator at a time, no concurrent Gradle build. API 35 requested 1536 MiB but the official Google APIs image enforces 2560 MiB; actual setting is recorded. 480×800, one vCPU, SwiftShader, no KVM. A 10-minute boot checkpoint is diagnostic, not a claim that boot failed; retain useful progress and use actual provider/framework readiness before app installation. Official API 35 AOSP/default and AOSP ATD alternatives were confirmed available if Google-image startup is impractical.

Freeze and hash the original 1.0.1 main/test APKs before baseline experiments. Preserve early environment initialization failures separately from app failures. After parent build, install in place with the same signature, verify versionCode/name/hash, run short matrix first, then provider and long cases with explicit logs and progress. Never weaken assertions merely to obtain green results. Keep each failed and corrected round separate.

Evidence correction: the original API 28 `EosProbe` printed only the tail packets. Earlier `time=0` shorthand was an inference from successful execution of the old `sampleTime>=0` loop, not a saved first-packet measurement. Exact API 28 first timestamps must be established with the new `TrackProbe`; API 35 negative/-1 values above are direct measurements.

The complete Google APIs 35 image produced valid native MediaExtractor probes but did not reach framework boot completion in the initial observation period. Its first APK installation attempt failed because Settings provider was not installed yet; later attempts timed out without `Success`. No app baseline tests were counted from that environment. Logs are retained under `api35/`; userdata was preserved when that emulator was stopped. The official AOSP ATD API 35 image is now used for headless instrumented logic/provider tests, not for SystemUI/predictive-back claims. Both images enforce a 2560 MiB RAM minimum despite the requested 1536 MiB.

Final direct API28 comparison is now recorded under api28/: first sample time0/track1/read258 for default, edited -1us, and nonnegative controls. API35 exact same bytes expose -23219/-1/0 respectively. The initial inference has been replaced with measured evidence.
