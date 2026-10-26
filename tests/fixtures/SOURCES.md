# Test media sources

## public-sample.mp4 — Sintel official trailer (480p)

- Author / attribution: © copyright Blender Foundation | www.sintel.org
- Official project download page: https://durian.blender.org/download/
- Official source file: https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4
- Official licensing statement: https://durian.blender.org/sharing/
- License: Creative Commons Attribution 3.0 Unported (CC BY 3.0)
- License terms: https://creativecommons.org/licenses/by/3.0/
- Downloaded: 2026-10-25 UTC.
- Changes: none. This is the entire official trailer file, renamed locally only; not a truncated download or clipped excerpt. Existing credits are preserved.
- Size: 4,372,373 bytes.
- SHA256: `b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`
- ffprobe: MP4; H.264 video; AAC stereo audio at 48,000 Hz; duration 52.208333 seconds. Full metadata in `public-sample.ffprobe.json`.
- Validation: `ffmpeg -v error -i public-sample.mp4 -f null -` completed with exit code 0 (all video and audio decoded).

Only used as a local functional test fixture. Not bundled in the installed application. The Blender and Sintel project names identify the original work and do not imply endorsement; logos/trademarks are not licensed by the content license.
