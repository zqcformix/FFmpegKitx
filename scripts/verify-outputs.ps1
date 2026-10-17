# Checks trim and split outputs against their input with desktop FFmpeg, independently of the
# library's own MediaInspector. Pull the outputs first, e.g. after running instrumented tests with
# `-e keepOutputs true`:
#
#   adb exec-out run-as io.github.nova.ffmpegkit.test cat cache/regression-test/out/vfr-trim.mp4 > vfr-trim.mp4
#
#   pwsh scripts/verify-outputs.ps1 -Mode trim  -InputFile av_vfr.mp4 -Outputs vfr-trim.mp4
#   pwsh scripts/verify-outputs.ps1 -Mode split -InputFile av_vfr.mp4 -Outputs vfr-0001.mp4, vfr-0002.mp4, ...
#   pwsh scripts/verify-outputs.ps1 -Mode decode -Outputs offset-trim.mp4
#
# Every mode decodes each output with `-err_detect explode` and checks that its first video packet is
# a keyframe. `trim`: per stream, the output's packets (size and MD5 from framemd5) are one contiguous
# run of the input's. `split`: per stream, the outputs concatenated in the given order equal the
# input's packets. Use `decode` when the container changes the packet data (e.g. MPEG-TS to MP4).
# Exits with 1 if any check fails.
param(
    [Parameter(Mandatory)][ValidateSet("trim", "split", "decode")][string]$Mode,
    [string]$InputFile,
    [Parameter(Mandatory)][string[]]$Outputs,
    [string]$FFmpeg = "ffmpeg",
    [string]$FFprobe = "ffprobe"
)

$ErrorActionPreference = "Stop"
if ($Mode -ne "decode" -and -not $InputFile) { throw "-InputFile is required for -Mode $Mode" }

# Packets per stream index, as "size,md5" in read order.
function Get-Packets([string]$file) {
    $lines = & $FFmpeg -v error -i $file -map 0 -c copy -f framemd5 - 2>&1
    if ($LASTEXITCODE -ne 0) { throw "framemd5 failed for ${file}: $lines" }
    $streams = @{}
    foreach ($line in $lines) {
        if ($line -match "^#" -or -not $line.Trim()) { continue }
        $fields = $line.Split(",") | ForEach-Object { $_.Trim() }
        $index = [int]$fields[0]
        if (-not $streams.ContainsKey($index)) { $streams[$index] = [System.Collections.Generic.List[string]]::new() }
        $streams[$index].Add($fields[4] + "," + $fields[5])
    }
    $streams
}

function Find-Run($all, $part) {
    for ($start = 0; $start -le $all.Count - $part.Count; $start++) {
        $match = $true
        for ($i = 0; $i -lt $part.Count; $i++) {
            if ($all[$start + $i] -ne $part[$i]) { $match = $false; break }
        }
        if ($match) { return $start }
    }
    -1
}

$failures = 0
function Report([bool]$ok, [string]$message) {
    if ($ok) { Write-Host "ok    $message" } else { Write-Host "FAIL  $message"; $script:failures++ }
}

foreach ($output in $Outputs) {
    $name = Split-Path -Leaf $output
    $errors = & $FFmpeg -v error -err_detect explode -i $output -f null - 2>&1
    Report ($LASTEXITCODE -eq 0 -and -not $errors) "$name decodes without errors $errors"
    $first = & $FFprobe -v error -select_streams v -show_entries packet=flags -of csv=p=0 $output | Select-Object -First 1
    if ($first) { Report ($first -match "K") "$name starts its video with a keyframe ($first)" }
}

if ($Mode -eq "trim") {
    $source = Get-Packets $InputFile
    foreach ($output in $Outputs) {
        $packets = Get-Packets $output
        foreach ($stream in $packets.Keys | Sort-Object) {
            $start = Find-Run $source[$stream] $packets[$stream]
            Report ($start -ge 0) ("$(Split-Path -Leaf $output) stream ${stream}: {0} packets = input[{1}..{2}]" -f $packets[$stream].Count, $start, ($start + $packets[$stream].Count - 1))
        }
    }
} elseif ($Mode -eq "split") {
    $source = Get-Packets $InputFile
    $joined = @{}
    foreach ($output in $Outputs) {
        $packets = Get-Packets $output
        foreach ($stream in $packets.Keys) {
            if (-not $joined.ContainsKey($stream)) { $joined[$stream] = [System.Collections.Generic.List[string]]::new() }
            $joined[$stream].AddRange($packets[$stream])
        }
    }
    $sourceStreams = @($source.Keys | Sort-Object) -join ","
    $joinedStreams = @($joined.Keys | Sort-Object) -join ","
    Report ($sourceStreams -eq $joinedStreams) "same streams as the input ($sourceStreams)"
    foreach ($stream in $source.Keys | Sort-Object) {
        $same = $joined.ContainsKey($stream) -and (($source[$stream] -join "|") -eq ($joined[$stream] -join "|"))
        Report $same ("stream ${stream}: {0} input packets, {1} in the outputs, identical in order" -f $source[$stream].Count, $joined[$stream].Count)
    }
}

if ($failures -gt 0) { Write-Host "$failures check(s) failed"; exit 1 }
Write-Host "all checks passed"
