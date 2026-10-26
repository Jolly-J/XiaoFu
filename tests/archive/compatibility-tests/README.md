# 1.0.2 synthetic compatibility matrix

No user media or secret is used. Generate with `./compatibility-tests/prepare-media.sh` (FFmpeg/Python required). All media is synthetic color frames and tone waves; it is not included in the source delivery.

## Media

| Fixture | Purpose |
|---|---|
| aac-editlist-default.mp4 | 2s mono AAC with legal encoder priming; ffprobe first PTS ≈ -23220us |
| aac-first-minus-one-us.mp4 | Same bitstream, valid audio mdhd/STTS/elst timebase rewrite; first packet PTS exactly -1us |
| aac-nonnegative-control.mp4 | Same bitstream without edit-list offset |
| lc-h264.mp4 | 8s 44.1kHz stereo AAC-LC/H264 baseline |
| fragmented.mp4 | fragmented MP4, empty moov/default-base-is-moof |
| lc.mov | QuickTime container |
| lc-hevc.mp4 | HEVC video, same AAC packets; no video decode required |
| multitrack.mp4 | First audio 44.1kHz stereo, second audio 48kHz mono; deterministic first-track choice |
| opus.webm | Opus decode/transcode/WAV path |
| mp3-track.mp4 | MP3 audio in MP4, platform conversion path |
| long-media/long-972s.mp4 | 16:12 duration, 44.1kHz stereo AAC/H264, 128x96@1fps; ~16MB |

FFprobe JSON, full FFmpeg decode success, and hashes are in `reports/`. The available FFmpeg has no libfdk_aac. No fake AAC-HE profile is claimed or used; valid HE-AAC remains an explicit coverage gap.

## Android tests

`CompatibilityMatrixTest`: 15 methods. Each normal media case exercises M4A and WAV; AAC verifies packet count and framed-byte SHA256 including preroll, timestamps within two PCM-frame rounding units, and WAV duration within200ms. Non-AAC checks real resulting AAC and PCM durations. Android35 specifically verifies the -1us sample fixture's first timestamp while it remains a valid audio track.

Provider modes: known length offset0; known length offset4096 with surrounding padding; unknown length offset0; non-seekable pipe. The test APK provider exposes only a fixed generated clip; arbitrary paths are rejected. `UNKNOWN_LENGTH` plus nonzero offset is deliberately not generated because Android's AssetFileDescriptor contract forbids it.

Long test is opt-in through fixture availability: push `long-media/long-972s.mp4` into target app cache as `compat-long-972s.mp4`, then run method `long972sStereo44100`. It emits an explicit assumption/skip if missing, which is not a pass. Other14 tests are embedded fixtures (~1.1MB total).

No tests have been run by the fixture author; parent/device runner owns build/device execution and final interpretation. Unsupported platform formats or non-seekable URI failures are not hidden by lowering assertions.

AFD contract reference: https://developer.android.com/reference/android/content/res/AssetFileDescriptor (constructor requires offset0 for UNKNOWN_LENGTH).

## Failure diagnostics

From the next test build, `CompatibilityMatrixTest` uses JUnit `@After` to save
`Diagnostics.copySummary()` under the target application's private
`files/compat-diagnostics/<method>.json`. This contains only the application's
privacy-filtered metadata, never raw exception messages, URIs, keys, or media.
A process-level kill/ANR can prevent `@After`; retain runner/logcat evidence then.
The summary includes its bounded in-process run history, so a method file can
include preceding test operations; use run IDs and statuses when interpreting it.

`codec-probe/` contains a standalone AAC encoder/decoder profile capability probe
and optional actual platform-generated HE fixtures. Capability declarations alone
are not counted as successful HE input compatibility tests.

## Actual platform HE-AAC fixtures

API 35 ATD `c2.android.aac.encoder` genuinely produced AAC profiles 5 and 29 from
8 seconds of generated 44.1 kHz stereo PCM. Independent ffprobe identified
HE-AAC and HE-AACv2 respectively, both 44.1 kHz stereo, audio duration 7.987664 s;
FFmpeg full decode exited successfully. We losslessly muxed those audio streams
with the existing synthetic H.264 video into `platform-he-profile-{5,29}.mp4`.
Two added Android matrix methods verify both M4A packet identity and WAV duration.
Host profile/decode verification is NOT an Android conversion pass. Runtime source
probe output is under `verification-1.0.2/atd35/aac-profiles/probe.txt`.
These clips are recreated using codec-probe on a platform with a genuine HE encoder,
not using FFmpeg native AAC profile signaling. A device without a declared encoder
cannot regenerate them; that does not imply a decoder is unavailable.

## Streaming provider boundaries (round 3)

The test-only Java provider exposes additional fixed synthetic modes: empty,
stalled, reliable-error, pipe-offset (4096-byte prefix and declared media length),
and pipe-truncated (valid media bytes but declared length 1000 bytes larger).
Tests require cleanup of `cache/source-staging` after success, error, and
cancellation. The cancellation test times out after 1 second and requires return
within 3 seconds; a 5-second independent watchdog closes the synthetic writer so
an incorrect blocking implementation fails rather than hanging all later tests.
The reliable pipe sends a complete valid clip, then `closeWithError`, which must
not be confused with successful EOF. Offset-pipe output packets are compared to
the original synthetic clip, including framed SHA-256 and packet count.

API 35 standalone decode of the genuine HE-AACv2 fixture confirmed extractor core
metadata 22050 Hz / one channel / profile 29, then decoder output 44100 Hz / two
channels and EOS after 1409024 PCM bytes. Only this fixture's source assertion is
one channel; its output still must be stereo at 44100 Hz with the same duration
and packet-preservation assertions. Other fixtures' source expectations are
unchanged. This is not evidence about API 28 metadata until that device is tested.
