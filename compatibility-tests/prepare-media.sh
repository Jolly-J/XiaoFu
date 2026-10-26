#!/usr/bin/env bash
# Synthetic fixtures only. Requires FFmpeg with libx264/libx265/libopus/libmp3lame.
# No videos or credentials are included in source delivery.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/androidTest/assets/compat
mkdir -p "$OUT" compatibility-tests/reports compatibility-tests/long-media
common=(-hide_banner -loglevel error -y -threads 2 -filter_threads 1 -filter_complex_threads 1)
# Two different tones identify channels and permit later waveform alignment checks.
ffmpeg "${common[@]}" -f lavfi -i 'color=c=blue:s=128x96:r=2' -f lavfi -i 'aevalsrc=0.2*sin(2*PI*440*t)|0.2*sin(2*PI*660*t):s=44100' -t 8 -c:v libx264 -preset ultrafast -threads 1 -pix_fmt yuv420p -c:a aac -profile:a aac_low -b:a 128k -movflags +faststart "$OUT/lc-h264.mp4"
ffmpeg "${common[@]}" -i "$OUT/lc-h264.mp4" -map 0 -c copy -movflags +frag_keyframe+empty_moov+default_base_moof "$OUT/fragmented.mp4"
ffmpeg "${common[@]}" -i "$OUT/lc-h264.mp4" -map 0 -c copy "$OUT/lc.mov"
ffmpeg "${common[@]}" -i "$OUT/lc-h264.mp4" -map 0 -c:v libx265 -preset ultrafast -x265-params pools=none:frame-threads=1:log-level=error -tag:v hvc1 -c:a copy "$OUT/lc-hevc.mp4"
ffmpeg "${common[@]}" -i "$OUT/lc-h264.mp4" -f lavfi -i 'sine=frequency=880:sample_rate=48000' -t 8 -map 0:v -map 0:a -map 1:a -c:v copy -c:a:0 copy -c:a:1 aac -b:a:1 64k -metadata:s:a:0 language=eng -metadata:s:a:1 language=zho "$OUT/multitrack.mp4"
ffmpeg "${common[@]}" -i "$OUT/lc-h264.mp4" -c:v libvpx -threads 1 -c:a libopus -b:a 96k "$OUT/opus.webm"
ffmpeg "${common[@]}" -i "$OUT/lc-h264.mp4" -c:v copy -c:a libmp3lame -b:a 128k "$OUT/mp3-track.mp4"
# Explicit negative-preroll fixtures (mono) copied in initial run from device_tests' identical generator.
ffmpeg "${common[@]}" -f lavfi -i 'color=c=blue:s=128x96:r=10' -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 2 -c:v mpeg4 -c:a aac -shortest "$OUT/aac-editlist-default.mp4"
python3 compatibility-tests/make-negative-one.py "$OUT/aac-editlist-default.mp4" "$OUT/aac-first-minus-one-us.mp4"
ffmpeg "${common[@]}" -i "$OUT/aac-editlist-default.mp4" -map 0 -c copy -use_editlist 0 "$OUT/aac-nonnegative-control.mp4"
# Long file intentionally kept OUTSIDE test APK. Push to app cache using device runner.
ffmpeg "${common[@]}" -f lavfi -i 'color=c=navy:s=128x96:r=1' -f lavfi -i 'aevalsrc=0.2*sin(2*PI*440*t)|0.2*sin(2*PI*660*t):s=44100' -t 972 -c:v libx264 -preset ultrafast -threads 1 -pix_fmt yuv420p -c:a aac -profile:a aac_low -b:a 128k -movflags +faststart compatibility-tests/long-media/long-972s.mp4
for file in "$OUT"/* compatibility-tests/long-media/*; do
  base=$(basename "$file")
  ffprobe -v error -show_format -show_streams -of json "$file" > "compatibility-tests/reports/$base.ffprobe.json"
  ffmpeg -v error -i "$file" -f null -
done
sha256sum "$OUT"/* compatibility-tests/long-media/* > compatibility-tests/reports/SHA256SUMS
