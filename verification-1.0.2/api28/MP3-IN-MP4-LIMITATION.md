# Measured Android 9 platform limitation (retained failed test)

The final 1.0.2 API28 short suite ran all 36 methods: 35 passed and mp3TrackInMp4 failed. It is not skipped or relabeled as a pass.

Evidence chain:

1. The synthetic MP4 is 132387 bytes. SHA-256 `9421613c8ca5bced9fdaba7d3930d08e35d7c2ddb8817fd1b50975a9e48876d9` matches both the host fixture and exact frozen instrumentation APK asset.
2. Host ffprobe reports H.264 video plus an MP3 audio stream, 44100 Hz stereo, 8.010 seconds. Fixture full FFmpeg decode was validated during matrix preparation.
3. Direct API28 MediaExtractor probe emits `NO_AUDIO` and no `TRACK` entries (the probe prints every track), so no readable audio track is exposed. Its native log explicitly says `MPEG4Extractor: MP3 track in MP4/3GPP file is not supported`.
4. App API28 diagnostic reports NO_AUDIO_TRACK / SOURCE_SCAN / OPEN_SOURCE. This is a known media capability failure, not the negative-PTS AAC bug.
5. On the same frozen final main/test APKs, API35's mp3TrackInMp4 method passes both M4A transcoding and WAV conversion. Its saved diagnostic records input `audio/mpeg`, trackCount2, selectedTrack1, 44100 Hz stereo, COMPLETE for both outputs.

No custom MP4 demuxer is being introduced for this platform limitation. The product supports tracks readable/decodable by the device's platform codecs; it does not promise every container/codec combination. API28 versus API35 behavior here is measured on the official emulator images, not assumed for every vendor device or every intervening API level.

Parent explicitly authorized continuing the independent final AAC long regression and UI checks while retaining this failed compatibility case in the final count. Main APK hash and test assertions are unchanged.
