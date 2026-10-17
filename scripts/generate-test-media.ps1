# Regenerates the media samples used by the instrumented tests.
#
#   pwsh scripts/generate-test-media.ps1 [-FFmpeg path\to\ffmpeg.exe]
#
# The samples are synthetic (test pattern + sine tone) so they carry no third-party content.
# Tests assert on their structure, so keep these properties when changing the commands:
#   - 10 s long, video 320x240 at 30 fps, H.264 with B-frames and a keyframe exactly every 1 s
#   - audio AAC-LC 44.1 kHz stereo
param(
    [string]$FFmpeg = "ffmpeg"
)

$ErrorActionPreference = "Stop"
$out = Join-Path $PSScriptRoot "..\ffmpegkit\src\androidTest\assets\media"
New-Item -ItemType Directory -Force $out | Out-Null

$video = @(
    "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=10"
)
$audio = @(
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=10"
)
$h264 = @(
    "-c:v", "libx264", "-profile:v", "main", "-preset", "veryfast", "-crf", "30",
    "-pix_fmt", "yuv420p", "-bf", "2", "-g", "30", "-keyint_min", "30", "-sc_threshold", "0",
    "-x264-params", "threads=1"
)
$aac = @("-c:a", "aac", "-b:a", "64k", "-ac", "2")
$global = @("-hide_banner", "-loglevel", "error", "-y")
$output = @("-map_metadata", "-1", "-fflags", "+bitexact")

function Invoke-FFmpeg([string[]]$arguments) {
    & $FFmpeg @arguments
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed: $arguments" }
}

Invoke-FFmpeg ($global + $video + $audio + $h264 + $aac + $output + @("-shortest", (Join-Path $out "av_h264_aac.mp4")))
Invoke-FFmpeg ($global + $video + $h264 + $output + @((Join-Path $out "video_h264.mp4")))
Invoke-FFmpeg ($global + $audio + $aac + $output + @((Join-Path $out "audio_aac.m4a")))

Get-ChildItem $out | Select-Object Name, Length
