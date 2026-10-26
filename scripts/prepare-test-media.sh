#!/usr/bin/env bash
# Rebuild instrumentation media; intentionally excluded from the source archive.
# Requires FFmpeg with AAC, libvpx/libvorbis/libx265, curl, and sha256sum.
set -euo pipefail
cd "$(dirname "$0")/.."
assets=app/src/androidTest/assets
mkdir -p "$assets" test-media
ffmpeg -y -hide_banner -loglevel error -f lavfi -i color=c=blue:s=128x96:r=10 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 2 -c:v mpeg4 -c:a aac -shortest "$assets/aac-video.mp4"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i color=c=blue:s=128x96:r=10 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 2 -c:v libvpx -c:a libvorbis -shortest "$assets/vorbis-video.webm"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i color=c=blue:s=128x96:r=10 -t 1 -c:v mpeg4 -an "$assets/silent-video.mp4"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i color=c=navy:s=128x96:r=5 -f lavfi -i sine=frequency=660:sample_rate=44100 -t 1 -c:v libx265 -x265-params pools=none:frame-threads=1:log-level=error -tag:v hvc1 -c:a aac -shortest "$assets/hevc-aac-video.mp4"
# © Blender Foundation | www.sintel.org, CC BY 3.0. No changes.
# Provenance and full licensing links: test-media/SOURCES.md.
curl --fail --location --retry 2 'https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4' -o test-media/public-sample.mp4
printf '%s  %s\n' b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254 test-media/public-sample.mp4 | sha256sum --check
ffmpeg -v error -i test-media/public-sample.mp4 -f null -
cp test-media/public-sample.mp4 "$assets/public-sintel.mp4"
for file in "$assets"/*; do ffprobe -v error -show_entries stream=codec_name,sample_rate,channels -of compact "$file"; done
printf '\nFixtures ready. Run ./gradlew connectedDebugAndroidTest with a connected Android device.\n'
