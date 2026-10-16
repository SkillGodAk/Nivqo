<#
.SYNOPSIS
    Fuzz the DASH manifest reader and the MP4 joiner on this machine, within set limits.

.DESCRIPTION
    DashManifest reads what Facebook's servers send, and Mp4Join reads the bytes of two downloaded
    files. Their tests cover the shapes someone thought of. This runs Jazzer, a coverage-guided JVM
    fuzzer, over both for the shapes nobody did. It's opt-in and local: no build task, test run or
    gate calls it, and nothing of Jazzer's goes into the extension or the Gradle build.

    The two parsers are pure Java, so this compiles them with javac straight from
    extensions/facebook/src/main, without Android or Gradle. Three small pieces they reach for live
    beside Android code (Downloader.Progress, VideoTranscode.sourceType and AacReencode.isXhe), so
    it copies just those out of the real sources into shims, and a change to their shape stops the
    run here rather than fuzzing a stale copy. The targets, the seed builder and the planted-bug
    reader are test sources (DashManifestFuzz, Mp4JoinFuzz, ParserFuzzSeeds, FaultyBoxFuzz), and
    ParserFuzzSeedsTest runs the seeds and every saved finding in the ordinary suite.

    Before it trusts a clean run it fuzzes FaultyBoxFuzz, a box reader with a planted bug, and the
    run fails unless Jazzer finds that bug in time. Then each target runs for -Seconds in one
    low-priority process, in a slot of the machine's build queue when there is one.

    An expected refusal (an IOException from the joiner, an empty answer from the manifest reader)
    is fine. An unchecked exception, a hang past -TimeoutSeconds, running out of heap, a join that
    stops on its own or writes past -MaxOutputMB is a finding: it's minimized and left under
    -FindingsPath, and the run exits 1. Copy the minimized file into tools/fuzz/regressions/<target>/
    and fix the parser, and ParserFuzzSeedsTest replays it on every test run after.

    Each input's files live in a folder of their own, removed before the next input. The run's work
    folder goes when the run ends, and a later run removes one an interrupted run left.

    What a run finds stays for the next one. Jazzer names each input it adds to a target's corpus
    by the SHA-1 of its bytes, and those are kept in build/fuzz/corpus/<target>. The next run starts
    from them, the seeds and a copy of every file in tools/fuzz/regressions/<target>/, which is only
    ever read. A kept file whose bytes don't match its name is dropped, and the folder is held to
    -CorpusMaxFiles files and -CorpusMaxMB, oldest first. Only files directly in it are removed, and
    a kept folder that's a link stops the run before anything is read or removed. The planted-bug
    check starts from its seeds alone each time, so what it found before can't make it easier.

    Every target run, clean or not, leaves build/fuzz/runs/<run>-<target>.json and the whole Jazzer
    log beside it. The record has the seed, the Jazzer and JDK versions, the commit, the seconds
    asked for and taken, the executions, the kept corpus before and after, libFuzzer's coverage
    figures, why the run ended and the off-hours window it ran in. The newest -KeepRuns of each
    target stay.

    It runs between 19:00 and 07:00 local time. A run that starts outside those hours, or whose
    fuzzing wouldn't be over by 07:00, does nothing and exits 3 unless -AllowDaytime is passed. The
    check is made again once a build queue slot is free.

    Exit codes: 0 when the planted bug was found and every target ran clean, 1 on a finding, 2 when
    the harness itself failed, 3 when it didn't run because of the hour.

.PARAMETER Target
    dash, mp4 or all (the default).

.PARAMETER Seconds
    How long each target runs.

.PARAMETER Jazzer
    The folder of Jazzer's standalone release (jazzer.exe and jazzer_standalone.jar). Left out, it's
    HUSHFACEBOOK_JAZZER_HOME, then %LOCALAPPDATA%\Programs\jazzer\0.30.0.

.PARAMETER Seed
    libFuzzer's random seed, to repeat a run. Left at 0, each run picks one and records it.

.PARAMETER CorpusMaxFiles
    The most inputs kept for a target between runs. libFuzzer keeps an input only when it reaches
    something no other input did, so a corpus grows fast and then slowly: a 30-second run kept 206
    manifest inputs and 140 MP4 ones. 4096 leaves room for many nights. Each run starts by running
    every kept input once, and at the manifest target's 150 inputs a second that's under half a
    minute.

.PARAMETER CorpusMaxMB
    The most bytes kept for a target, in MB. That run's manifest inputs averaged under 10 KB, so the
    count bound comes first, and 64 still holds about 250 inputs at the MP4 target's longest or 470
    at the manifest's. Both targets together stay under 128 MB.

.PARAMETER KeepRuns
    How many run records and logs stay for each target, newest first.

.PARAMETER AllowDaytime
    Runs outside 19:00 to 07:00, or past 07:00.

.PARAMETER Now
    The time the run takes as its start for the hours check. Left out, it's now.

.EXAMPLE
    scripts/fuzz-parsers.ps1 -Seconds 600

.EXAMPLE
    scripts/fuzz-parsers.ps1 -Target mp4 -Seconds 3600 -HeapMB 2048
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'dash', 'mp4')][string]$Target = 'all',
    [ValidateRange(10, 86400)][int]$Seconds = 300,
    [ValidateRange(10, 1800)][int]$SelfCheckSeconds = 180,
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 10,
    [ValidateRange(256, 16384)][int]$HeapMB = 1024,
    [ValidateRange(512, 32768)][int]$RssLimitMB = 3072,
    [ValidateRange(1, 4096)][int]$MaxOutputMB = 256,
    [ValidateRange(0, 16777216)][int]$MaxInputBytes = 0,
    [ValidateRange(0, 2147483647)][int]$Seed = 0,
    [ValidateRange(1, 1000000)][int]$CorpusMaxFiles = 4096,
    [ValidateRange(1, 65536)][int]$CorpusMaxMB = 64,
    [ValidateRange(1, 10000)][int]$KeepRuns = 30,
    [string]$Jazzer,
    [string]$Java,
    [string]$FindingsPath,
    [switch]$SelfCheckOnly,
    [switch]$AllowDaytime,
    [datetime]$Now = (Get-Date),
    [string]$Root
)

$ErrorActionPreference = 'Stop'
$script:clock = [Diagnostics.Stopwatch]::StartNew()
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

$package = 'app.morphe.extension.facebook.download'
$mainSources = Join-Path $Root 'extensions/facebook/src/main/java/app/morphe/extension/facebook/download'
$testSources = Join-Path $Root 'extensions/facebook/src/test/java/app/morphe/extension/facebook/download'
$capturedManifest = Join-Path $Root 'extensions/facebook/src/test/resources/app/morphe/extension/facebook/download/meta-public-dash.mpd'

# Each target: its class, the longest input it gets and the tokens its dictionary starts from. The
# manifest's runs past DashManifest.MAX_CHARS, so the limit itself gets reached.
$targets = [ordered]@{
    dash = @{ Class = 'DashManifestFuzz'; MaxLen = 140000; Tokens = @(
        '<AdaptationSet', '</AdaptationSet>', '<Representation', '</Representation>', '/>', '<BaseURL>', '</BaseURL>',
        'mimeType="', 'contentType="', 'codecs="', 'width="', 'height="', 'bandwidth="', 'FBQualityLabel="',
        'https://', '&amp;', '&lt;', '&#', 'video/mp4', 'audio/mp4', 'avc1.64001F', 'vp09.00.31.08', 'av01.0.05M.08',
        'hvc1.1.6.L93.90', 'mp4a.40.2', 'mp4a.40.5', 'mp4a.40.42', '1080p60', '720p') }
    mp4 = @{ Class = 'Mp4JoinFuzz'; MaxLen = 262144; Tokens = @(
        'ftyp', 'moov', 'mvhd', 'trak', 'tkhd', 'edts', 'elst', 'mdia', 'mdhd', 'hdlr', 'minf', 'stbl', 'stsd', 'stsz',
        'stts', 'ctts', 'stss', 'stsc', 'stco', 'co64', 'mvex', 'trex', 'moof', 'traf', 'tfhd', 'tfdt', 'trun', 'mdat',
        'vide', 'soun', 'avc1', 'vp09', 'av01', 'hvc1', 'mp4a', 'encv', 'enca', 'free') }
    faulty = @{ Class = 'FaultyBoxFuzz'; MaxLen = 4096; Tokens = @('ftyp', 'free', 'mdat') }
}

function Resolve-FullPath {
    param([string]$Path)
    return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
}

# Jazzer reads its list flags split on ';' and takes '\' as an escape, so paths go to it with '/'.
function ConvertTo-JazzerPath {
    param([string]$Path)
    return $Path.Replace('\', '/')
}

# The run's clock: -Now when it started, plus the time since.
function Get-RunClock {
    return $Now.AddMilliseconds($script:clock.ElapsedMilliseconds)
}

function Format-RunTime {
    param([datetime]$At)
    return $At.ToString('yyyy-MM-ddTHH:mm:sszzz', [Globalization.CultureInfo]::InvariantCulture)
}

# The off-hours window [At] falls in, 19:00 to 07:00 local, or nothing when [At] is in the day.
function Get-FuzzWindow {
    param([datetime]$At)
    if ($At.Hour -ge 19) { $start = $At.Date.AddHours(19) }
    elseif ($At.Hour -lt 7) { $start = $At.Date.AddDays(-1).AddHours(19) }
    else { return $null }
    return [pscustomobject]@{ Start = $start; End = $start.AddHours(12) }
}

# Why a run can't start now, or nothing when it can. [PlannedSeconds] of fuzzing have to be over
# by 07:00.
function Get-WindowRefusal {
    param([int]$PlannedSeconds)
    if ($AllowDaytime) { return $null }
    $at = Get-RunClock
    $clockText = $at.ToString('HH:mm', [Globalization.CultureInfo]::InvariantCulture)
    $window = Get-FuzzWindow $at
    if (-not $window) {
        return "It's $clockText, and parser fuzzing runs between 19:00 and 07:00. Pass -AllowDaytime to run it now."
    }
    if ($at.AddSeconds($PlannedSeconds) -gt $window.End) {
        return "$PlannedSeconds s of fuzzing from $clockText would run past 07:00. Pass a smaller -Seconds, or -AllowDaytime."
    }
    return $null
}

function Resolve-Jazzer {
    param([string]$Explicit)
    $candidates = @($Explicit, $env:HUSHFACEBOOK_JAZZER_HOME)
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Programs/jazzer/0.30.0' }
    foreach ($folder in @($candidates | Where-Object { $_ })) {
        $exe = Join-Path $folder 'jazzer.exe'
        if ((Test-Path -LiteralPath $exe -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path $folder 'jazzer_standalone.jar') -PathType Leaf)) {
            return (Resolve-FullPath $exe)
        }
    }
    throw ("No Jazzer found. Download the standalone release (jazzer-windows.tar.gz) from " +
        'https://github.com/CodeIntelligenceTesting/jazzer/releases, unpack it, and pass its folder with -Jazzer ' +
        'or set HUSHFACEBOOK_JAZZER_HOME.')
}

# The text of the member that starts at the first match of [Header], through its closing brace.
# Braces in string and character literals and in comments don't count.
function Get-JavaMember {
    param([string]$Path, [string]$Header)
    $text = [System.IO.File]::ReadAllText($Path)
    $match = [regex]::Match($text, $Header, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        throw "$Path has nothing matching '$Header' any more. The fuzz shims copy it from there, so scripts/fuzz-parsers.ps1 needs updating."
    }
    $depth = 0
    for ($i = $text.IndexOf('{', $match.Index); $i -ge 0 -and $i -lt $text.Length; $i++) {
        $c = $text[$i]
        if ($c -eq '"' -or $c -eq "'") {
            $quote = $c
            for ($i++; $i -lt $text.Length -and $text[$i] -ne $quote; $i++) { if ($text[$i] -eq '\') { $i++ } }
            continue
        }
        if ($c -eq '/' -and $i + 1 -lt $text.Length -and $text[$i + 1] -eq '/') { $i = $text.IndexOf("`n", $i); if ($i -lt 0) { break }; continue }
        if ($c -eq '/' -and $i + 1 -lt $text.Length -and $text[$i + 1] -eq '*') { $i = $text.IndexOf('*/', $i + 2) + 1; if ($i -le 0) { break }; continue }
        if ($c -eq '{') { $depth++ }
        elseif ($c -eq '}') {
            $depth--
            if ($depth -eq 0) { return $text.Substring($match.Index, $i - $match.Index + 1) }
        }
    }
    throw "The member matching '$Header' in $Path never closes."
}

# The three Android-side pieces the parsers reach for, copied from the real sources, and the
# constants and annotation those copies name.
function Write-Shims {
    param([string]$Folder)
    $own = Join-Path $Folder ($package.Replace('.', '/'))
    New-Item -ItemType Directory -Force -Path $own, (Join-Path $Folder 'android/media'), (Join-Path $Folder 'androidx/annotation') | Out-Null
    $note = '// Generated by scripts/fuzz-parsers.ps1 from the class of this name in extensions/facebook/src/main.'
    $progress = Get-JavaMember (Join-Path $mainSources 'Downloader.java') '^[ \t]*interface\s+Progress\b'
    $sourceType = Get-JavaMember (Join-Path $mainSources 'VideoTranscode.java') '^[ \t]*static\s+\S+\s+sourceType\s*\('
    $aac = Join-Path $mainSources 'AacReencode.java'
    $isXhe = Get-JavaMember $aac '^[ \t]*static\s+boolean\s+isXhe\s*\('
    $xheType = [regex]::Match([System.IO.File]::ReadAllText($aac), '(?m)^[ \t]*static\s+final\s+int\s+XHE_OBJECT_TYPE\s*=\s*\d+\s*;')
    if (-not $xheType.Success) { throw "$aac no longer declares XHE_OBJECT_TYPE as a number. Update scripts/fuzz-parsers.ps1." }
    $files = @{
        'Downloader.java' = "package $package;`n$note`nimport java.util.*;`nfinal class Downloader {`n$progress`n}`n"
        'VideoTranscode.java' = "package $package;`n$note`nimport java.util.*;`nimport android.media.MediaFormat;`nfinal class VideoTranscode {`n$sourceType`n}`n"
        'AacReencode.java' = "package $package;`n$note`nimport java.util.*;`nimport androidx.annotation.Nullable;`nfinal class AacReencode {`n$($xheType.Value)`n$isXhe`n}`n"
    }
    foreach ($name in $files.Keys) { [System.IO.File]::WriteAllText((Join-Path $own $name), $files[$name]) }
    # Only whether sourceType answers null matters to DashManifest, so each constant is its own name.
    $constants = @([regex]::Matches($sourceType, 'MediaFormat\.(\w+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique |
        ForEach-Object { "    public static final String $_ = `"$_`";" })
    [System.IO.File]::WriteAllText((Join-Path $Folder 'android/media/MediaFormat.java'),
        "package android.media;`n// Generated by scripts/fuzz-parsers.ps1: the constants VideoTranscode.sourceType names.`npublic final class MediaFormat {`n$($constants -join "`n")`n}`n")
    [System.IO.File]::WriteAllText((Join-Path $Folder 'androidx/annotation/Nullable.java'),
        "package androidx.annotation;`n// Generated by scripts/fuzz-parsers.ps1.`npublic @interface Nullable {}`n")
    return @(Get-ChildItem -LiteralPath $Folder -Recurse -Filter '*.java' -File | ForEach-Object { $_.FullName })
}

# A libFuzzer dictionary of [Tokens].
function Write-Dictionary {
    param([string]$Path, [string[]]$Tokens)
    $lines = foreach ($token in $Tokens) { '"' + ($token.Replace('\', '\\').Replace('"', '\"')) + '"' }
    [System.IO.File]::WriteAllLines($Path, [string[]]$lines)
}

# Runs [File] hidden, at idle priority, for at most [WallSeconds], and answers its exit code and
# output. A process past its time is ended with what it started.
function Invoke-Quiet {
    param([string]$File, [string[]]$Arguments, [int]$WallSeconds, [hashtable]$Environment = @{})
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($name in $Environment.Keys) { $info.EnvironmentVariables[$name] = $Environment[$name] }
    if ($info.PSObject.Properties['ArgumentList']) {
        foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    } else {
        $info.Arguments = (@($Arguments | ForEach-Object {
            '"' + ($_ -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
        }) -join ' ')
    }
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    if (-not $process.Start()) { throw "Could not start $File." }
    try { $process.PriorityClass = [Diagnostics.ProcessPriorityClass]::Idle } catch { Write-Warning "Could not lower the priority of $File." }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $timedOut = -not $process.WaitForExit($WallSeconds * 1000)
    if ($timedOut) {
        if ($process.GetType().GetMethod('Kill', [type[]]@([bool]))) { $process.Kill($true) }
        else { & (Join-Path $env:WINDIR 'System32/taskkill.exe') /PID $process.Id /T /F | Out-Null }
        [void]$process.WaitForExit(10000)
    }
    $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    return [pscustomobject]@{ ExitCode = if ($timedOut) { -1 } else { $process.ExitCode }; TimedOut = $timedOut; Output = $output }
}

# The version [File] prints for [Arguments], the first group of [Pattern], or null.
function Get-ToolVersion {
    param([string]$File, [string[]]$Arguments, [string]$Pattern)
    $answer = Invoke-Quiet -File $File -Arguments $Arguments -WallSeconds 60 -Environment @{ JAVA_HOME = $script:javaHome }
    $match = [regex]::Match($answer.Output, $Pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    Write-Warning "$File didn't print its version, so the run records leave it out."
    return $null
}

# The commit [Root] has checked out, or null when it isn't a repository of its own.
function Get-RootCommit {
    $git = Get-Command git -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $git) { return $null }
    $answer = Invoke-Quiet -File $git.Source -Arguments @('-C', $Root, 'rev-parse', '--show-toplevel', 'HEAD') -WallSeconds 60
    $lines = @($answer.Output -split "`r?`n" | Where-Object { $_ })
    if ($answer.ExitCode -ne 0 -or $lines.Count -lt 2 -or $lines[1] -notmatch '\A[0-9a-f]{40,64}\z') { return $null }
    if ((Resolve-FullPath $lines[0]).TrimEnd('\', '/') -ne (Resolve-FullPath $Root).TrimEnd('\', '/')) { return $null }
    return $lines[1]
}

# Throws when [Path], or a folder above it up to [Top], is a link. Pruning removes files directly in
# a kept folder, and a kept folder linked to tools/fuzz/regressions would make those the findings.
function Assert-NotLinked {
    param([string]$Path, [string]$Top)
    $top = $Top.TrimEnd('\', '/')
    for ($at = $Path.TrimEnd('\', '/'); $at; $at = Split-Path -Parent $at) {
        $item = Get-Item -LiteralPath $at -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
            throw "$at is a link. What's kept under build/fuzz gets pruned, so it has to be a plain folder."
        }
        if ($at -eq $top) { break }
    }
}

function Get-Sha1Hex {
    param([string]$Path)
    $stream = [System.IO.File]::OpenRead($Path)
    try { $hash = $script:sha1.ComputeHash($stream) } finally { $stream.Dispose() }
    return ([System.BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant())
}

# The kept corpus in [Folder]: Valid, the plain files named by the SHA-1 of their bytes, the way
# Jazzer names what it adds, and Invalid, every other file directly in it.
function Read-KeptCorpus {
    param([string]$Folder)
    $valid = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    $invalid = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    if (Test-Path -LiteralPath $Folder -PathType Container) {
        foreach ($file in @(Get-ChildItem -LiteralPath $Folder -File -Force)) {
            $linked = ($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
            if (-not $linked -and $file.Name -cmatch '\A[0-9a-f]{40}\z' -and (Get-Sha1Hex $file.FullName) -ceq $file.Name) { $valid.Add($file) }
            else { $invalid.Add($file) }
        }
    }
    $bytes = [long]0
    foreach ($file in $valid) { $bytes += $file.Length }
    return [pscustomobject]@{ Valid = $valid; Invalid = $invalid; Bytes = $bytes }
}

# Copies the inputs Jazzer added in [WorkCorpus] into [Folder], drops what isn't a kept input, and
# removes the oldest past -CorpusMaxFiles and -CorpusMaxMB. Each removal is File.Delete on a file
# directly in [Folder], which takes a link away without touching what it points at.
function Save-KeptCorpus {
    param([string]$WorkCorpus, [string]$Folder)
    New-Item -ItemType Directory -Force -Path $Folder | Out-Null
    $added = 0
    foreach ($file in @(Get-ChildItem -LiteralPath $WorkCorpus -File)) {
        if ($file.Name -cnotmatch '\A[0-9a-f]{40}\z') { continue }
        $kept = Join-Path $Folder $file.Name
        if (Test-Path -LiteralPath $kept) { continue }
        # A run ended mid-write leaves a file that doesn't match its name.
        if ((Get-Sha1Hex $file.FullName) -cne $file.Name) { continue }
        [System.IO.File]::Copy($file.FullName, $kept)
        $added++
    }
    $read = Read-KeptCorpus $Folder
    $removed = 0
    foreach ($file in $read.Invalid) {
        Write-Host "[fuzz] dropping $($file.Name) from the kept corpus: it isn't an input named by its SHA-1"
        [System.IO.File]::Delete($file.FullName)
        $removed++
    }
    $oldestFirst = @($read.Valid | Sort-Object LastWriteTimeUtc, Name)
    $count = $oldestFirst.Count
    $bytes = $read.Bytes
    $maxBytes = [long]$CorpusMaxMB * 1024 * 1024
    for ($i = 0; $i -lt $oldestFirst.Count -and ($count -gt $CorpusMaxFiles -or $bytes -gt $maxBytes); $i++) {
        [System.IO.File]::Delete($oldestFirst[$i].FullName)
        $count--
        $bytes -= $oldestFirst[$i].Length
        $removed++
    }
    return [pscustomobject]@{ Added = $added; Removed = $removed; Files = $count; Bytes = $bytes }
}

# What libFuzzer printed about a run, each figure null when it printed none.
function Read-FuzzStats {
    param([string]$Output)
    $stats = [ordered]@{ seed = $null; executions = $null; execPerSecond = $null; newUnits = $null; peakRssMB = $null; coverage = $null }
    $lastUnit = $null
    foreach ($line in @($Output -split "`r?`n")) {
        if ($line -match '^INFO: Seed: (\d+)') { $stats.seed = [long]$Matches[1] }
        elseif ($line -match '^stat::number_of_executed_units:\s*(\d+)') { $stats.executions = [long]$Matches[1] }
        elseif ($line -match '^stat::average_exec_per_sec:\s*(\d+)') { $stats.execPerSecond = [long]$Matches[1] }
        elseif ($line -match '^stat::new_units_added:\s*(\d+)') { $stats.newUnits = [long]$Matches[1] }
        elseif ($line -match '^stat::peak_rss_mb:\s*(\d+)') { $stats.peakRssMB = [long]$Matches[1] }
        elseif ($line -match '^Done (\d+) runs in') { if ($null -eq $stats.executions) { $stats.executions = [long]$Matches[1] } }
        elseif ($line -match '^#(\d+)\s+\w+\s+cov: (\d+) ft: (\d+) corp: (\d+)/(\S+)') {
            $lastUnit = [long]$Matches[1]
            $stats.coverage = [ordered]@{ edges = [long]$Matches[2]; features = [long]$Matches[3]; corpusInputs = [long]$Matches[4]; corpusSize = $Matches[5] }
        }
    }
    if ($null -eq $stats.executions) { $stats.executions = $lastUnit }
    return $stats
}

# Writes [Record] and [Log] to build/fuzz/runs/<run>-[Name], then removes that target's oldest
# records and their logs past -KeepRuns. Nothing else in the folder is touched.
function Save-RunRecord {
    param([string]$Name, [System.Collections.IDictionary]$Record, [string]$Log)
    New-Item -ItemType Directory -Force -Path $script:runsRoot | Out-Null
    $base = Join-Path $script:runsRoot "$($script:runId)-$Name"
    [System.IO.File]::WriteAllText("$base.log", $Log)
    [System.IO.File]::WriteAllText("$base.json", (ConvertTo-Json -InputObject $Record -Depth 6) + "`n")
    $pattern = '\A\d{8}-\d{6}-\d{3}-' + [regex]::Escape($Name) + '\.json\z'
    $records = @(Get-ChildItem -LiteralPath $script:runsRoot -File | Where-Object { $_.Name -cmatch $pattern } | Sort-Object Name)
    for ($i = 0; $i -lt $records.Count - $KeepRuns; $i++) {
        [System.IO.File]::Delete($records[$i].FullName)
        [System.IO.File]::Delete([System.IO.Path]::ChangeExtension($records[$i].FullName, '.log'))
    }
}

# Fuzzes one target for [Seconds] and answers its findings, minimized where Jazzer could. The
# self-check starts from its seeds alone, keeps nothing and minimizes nothing.
function Invoke-Target {
    param([string]$Name, [int]$Seconds, [string]$Findings, [switch]$SelfCheck)
    $spec = $targets[$Name]
    $corpus = Join-Path $script:work "corpus/$Name"
    $dictionary = Join-Path $script:work "$Name.dict"
    Write-Dictionary $dictionary $spec.Tokens
    New-Item -ItemType Directory -Force -Path $Findings | Out-Null
    $maxLen = if ($MaxInputBytes -gt 0) { $MaxInputBytes } else { $spec.MaxLen }
    $seeds = @(Get-ChildItem -LiteralPath $corpus -File).Count
    $saved = 0
    $before = $null
    $kept = Join-Path $script:corpusRoot $Name
    if (-not $SelfCheck) {
        # Copies, so nothing Jazzer does to its corpus reaches the saved findings.
        $regressions = Join-Path $Root "tools/fuzz/regressions/$Name"
        if (Test-Path -LiteralPath $regressions -PathType Container) {
            foreach ($file in @(Get-ChildItem -LiteralPath $regressions -File)) {
                [System.IO.File]::Copy($file.FullName, (Join-Path $corpus "regression-$($file.Name)"))
                $saved++
            }
        }
        $before = Read-KeptCorpus $kept
        foreach ($file in $before.Valid) { [System.IO.File]::Copy($file.FullName, (Join-Path $corpus $file.Name), $true) }
        Write-Host "[fuzz] $Name starts from $seeds seeds, $saved saved findings and $($before.Valid.Count) kept inputs"
    }
    $jvm = @("-Xmx$($HeapMB)m", "-D$($script:tempProperty)=$(ConvertTo-JazzerPath $script:caseTemp)",
        "-Dhushfacebook.fuzz.maxOutputBytes=$([long]$MaxOutputMB * 1024 * 1024)") -join ';'
    $common = @(
        "--cp=$(ConvertTo-JazzerPath $script:classes)",
        "--target_class=$package.$($spec.Class)",
        "--instrumentation_includes=$package.**",
        "--jvm_args=$jvm",
        "-timeout=$TimeoutSeconds",
        "-rss_limit_mb=$RssLimitMB",
        "-max_len=$maxLen"
    )
    $environment = @{ JAVA_HOME = $script:javaHome }
    Write-Host "[fuzz] $Name for $Seconds s, inputs up to $maxLen bytes, $HeapMB MB heap, $TimeoutSeconds s an input, seed $($script:seed)"
    $startedAt = Get-RunClock
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $run = Invoke-Quiet -File $script:jazzerExe -WallSeconds ($Seconds + 180) -Environment $environment -Arguments ($common + @(
        "--reproducer_path=$(ConvertTo-JazzerPath $Findings)",
        "-dict=$(ConvertTo-JazzerPath $dictionary)",
        "-max_total_time=$Seconds",
        "-seed=$($script:seed)",
        "-print_final_stats=1",
        "-artifact_prefix=$(ConvertTo-JazzerPath $Findings)/",
        (ConvertTo-JazzerPath $corpus)
    ))
    $watch.Stop()
    $endedAt = Get-RunClock
    [System.IO.File]::WriteAllText((Join-Path $Findings 'jazzer.log'), $run.Output)
    $after = $null
    if (-not $SelfCheck) {
        $after = Save-KeptCorpus -WorkCorpus $corpus -Folder $kept
        Write-Host ("[fuzz] $Name kept corpus: $($after.Added) added, $($after.Removed) removed, $($after.Files) inputs, " +
            "$([math]::Round($after.Bytes / 1MB, 1)) MB")
    }
    $found = @(Get-ChildItem -LiteralPath $Findings -File | Where-Object { $_.Name -match '^(crash|timeout|oom|leak|slow-unit)-' })
    foreach ($finding in $found) {
        if ($SelfCheck -or $finding.Name -notlike 'crash-*') { continue }
        $minimized = Join-Path $Findings "minimized-$($finding.Name)"
        # Each step of the shrink writes a reproducer of its own, so they go in the work folder.
        $steps = New-Item -ItemType Directory -Force -Path (Join-Path $script:work "minimize-$Name")
        $shrink = Invoke-Quiet -File $script:jazzerExe -WallSeconds 300 -Environment $environment -Arguments ($common + @(
            "--reproducer_path=$(ConvertTo-JazzerPath $steps.FullName)",
            '-minimize_crash=1', '-max_total_time=120', '-runs=100000',
            "-exact_artifact_path=$(ConvertTo-JazzerPath $minimized)",
            (ConvertTo-JazzerPath $finding.FullName)
        ))
        [System.IO.File]::WriteAllText((Join-Path $Findings "minimize-$($finding.Name).log"), $shrink.Output)
    }
    $summary = @($run.Output -split "`r?`n" | Where-Object { $_ -match '^(==\d+== |#\d+\s+DONE|Done \d+ runs|stat::number_of_executed_units|stat::new_units_added|.*Java Exception|.*FuzzerSecurityIssue)' } | Select-Object -Last 8)
    $summary | ForEach-Object { Write-Host "  $_" }

    $crashes = @($found | Where-Object { $_.Name -like 'crash-*' })
    $reason = if ($found.Count -eq 0 -and $run.TimedOut) { 'wall-clock-limit' }
        elseif ($found.Count -eq 0 -and $run.ExitCode -ne 0) { 'jazzer-error' }
        elseif ($SelfCheck) { if ($crashes.Count -gt 0) { 'planted-bug-found' } else { 'planted-bug-missed' } }
        elseif ($found.Count -gt 0) { 'finding' }
        else { 'clean' }
    $stats = Read-FuzzStats $run.Output
    $window = Get-FuzzWindow $startedAt
    $record = [ordered]@{
        schema = 1
        run = $script:runId
        target = $Name
        targetClass = "$package.$($spec.Class)"
        seed = if ($null -ne $stats.seed) { $stats.seed } else { [long]$script:seed }
        jazzer = [ordered]@{ version = $script:jazzerVersion; path = $script:jazzerExe }
        jdk = [ordered]@{ version = $script:jdkVersion; home = $script:javaHome }
        commit = $script:commit
        startedAt = Format-RunTime $startedAt
        endedAt = Format-RunTime $endedAt
        window = if ($window) { [ordered]@{ start = Format-RunTime $window.Start; end = Format-RunTime $window.End } } else { $null }
        allowDaytime = [bool]$AllowDaytime
        requestedSeconds = $Seconds
        actualSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
        executions = $stats.executions
        execPerSecond = $stats.execPerSecond
        newInputs = $stats.newUnits
        peakRssMB = $stats.peakRssMB
        coverage = $stats.coverage
        corpus = [ordered]@{
            seeds = $seeds
            savedFindings = $saved
            keptBefore = if ($before) { $before.Valid.Count } else { $null }
            keptBytesBefore = if ($before) { $before.Bytes } else { $null }
            added = if ($after) { $after.Added } else { $null }
            removed = if ($after) { $after.Removed } else { $null }
            keptAfter = if ($after) { $after.Files } else { $null }
            keptBytesAfter = if ($after) { $after.Bytes } else { $null }
        }
        limits = [ordered]@{ heapMB = $HeapMB; rssLimitMB = $RssLimitMB; timeoutSeconds = $TimeoutSeconds; maxInputBytes = $maxLen; maxOutputMB = $MaxOutputMB }
        jazzerExitCode = $run.ExitCode
        exitReason = $reason
        findings = [string[]]@($found | ForEach-Object { $_.Name })
        log = "$($script:runId)-$Name.log"
    }
    Save-RunRecord -Name $Name -Record $record -Log $run.Output
    if ($found.Count -eq 0 -and $run.ExitCode -ne 0) {
        $tail = @($run.Output -split "`r?`n" | Select-Object -Last 15) -join "`n"
        throw "Jazzer ended with exit code $($run.ExitCode) on $Name and left no finding$(if ($run.TimedOut) { ', past its wall-clock limit' }):`n$tail"
    }
    return $found
}

$chosen = if ($Target -eq 'all') { @('dash', 'mp4') } else { @($Target) }
$plannedSeconds = $SelfCheckSeconds
if (-not $SelfCheckOnly) { $plannedSeconds += $Seconds * $chosen.Count }
$refusal = Get-WindowRefusal -PlannedSeconds $plannedSeconds
if ($refusal) {
    Write-Host "[fuzz] $refusal Nothing ran."
    exit 3
}

$exitCode = 2
$script:work = $null
$script:sha1 = [System.Security.Cryptography.SHA1]::Create()
try {
    # Work folders of runs that were stopped before their cleanup: no owner alive, or no owner file.
    $tempRoot = [System.IO.Path]::GetTempPath()
    foreach ($stale in @(Get-ChildItem -LiteralPath $tempRoot -Directory -Filter 'hushfacebook-fuzz-*' -ErrorAction SilentlyContinue)) {
        $ownerFile = Join-Path $stale.FullName 'owner.pid'
        $owner = if (Test-Path -LiteralPath $ownerFile) { (Get-Content -LiteralPath $ownerFile -Raw).Trim() } else { '' }
        $alive = $owner -match '^\d+$' -and $null -ne (Get-Process -Id ([int]$owner) -ErrorAction SilentlyContinue)
        if (-not $alive) {
            Write-Host "[fuzz] removing $($stale.Name), left by a run that was stopped"
            Remove-Item -LiteralPath $stale.FullName -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    $script:jazzerExe = Resolve-Jazzer $Jazzer
    $javaExe = Resolve-Java -Explicit $Java
    $javaCommand = Get-Command $javaExe -ErrorAction Stop
    $binFolder = Split-Path -Parent $javaCommand.Source
    $javac = @('javac.exe', 'javac') | ForEach-Object { Join-Path $binFolder $_ } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $javac) { throw "$($javaCommand.Source) has no javac beside it. Pass a JDK with -Java or HUSHFACEBOOK_JAVA." }
    $script:javaHome = Split-Path -Parent $binFolder
    $script:tempProperty = 'hushfacebook.fuzz.temp'

    # What's kept between runs, in folders of their own that a clean run's cleanup can't reach.
    $stateRoot = Resolve-FullPath (Join-Path $Root 'build/fuzz')
    $script:corpusRoot = Join-Path $stateRoot 'corpus'
    $script:runsRoot = Join-Path $stateRoot 'runs'
    foreach ($name in $chosen) { Assert-NotLinked -Path (Join-Path $script:corpusRoot $name) -Top $stateRoot }
    Assert-NotLinked -Path $script:runsRoot -Top $stateRoot

    if (-not $FindingsPath) { $FindingsPath = Join-Path $Root ("build/fuzz/" + (Get-Date).ToString('yyyyMMdd-HHmmss')) }
    $FindingsPath = Resolve-FullPath $FindingsPath
    $findingsPrefix = $FindingsPath.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    foreach ($state in @($script:corpusRoot, $script:runsRoot)) {
        if (($state + [System.IO.Path]::DirectorySeparatorChar).StartsWith($findingsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "-FindingsPath $FindingsPath holds $state, and a clean run removes its findings folder. Give the findings a folder of their own."
        }
    }

    $script:seed = if ($Seed -gt 0) { $Seed } else { Get-Random -Minimum 1 -Maximum ([int]::MaxValue) }
    $script:runId = (Get-Date).ToString('yyyyMMdd-HHmmss-fff', [Globalization.CultureInfo]::InvariantCulture)
    $script:work = Join-Path $tempRoot ("hushfacebook-fuzz-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $script:work | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $script:work 'owner.pid'), "$PID")
    $script:classes = Join-Path $script:work 'classes'
    $script:caseTemp = Join-Path $script:work 'cases'

    $shims = Write-Shims (Join-Path $script:work 'shims')
    $sources = @(
        @('DashManifest.java', 'DownloadQuality.java', 'Mp4Join.java') | ForEach-Object { Join-Path $mainSources $_ }
        @('DashManifestFuzz.java', 'Mp4JoinFuzz.java', 'FaultyBoxFuzz.java', 'ParserFuzzSeeds.java',
            'FragmentedMp4ForTests.java', 'PlainMp4ForTests.java') | ForEach-Object { Join-Path $testSources $_ }
        $shims
    )
    $empty = New-Item -ItemType Directory -Force -Path (Join-Path $script:work 'no-sources')
    $argumentFile = Join-Path $script:work 'javac.args'
    $javacArguments = @('--release', '11', '-encoding', 'UTF-8', '-nowarn', '-implicit:none', '-sourcepath', $empty.FullName, '-d', $script:classes) + $sources
    [System.IO.File]::WriteAllLines($argumentFile, [string[]]@($javacArguments | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }))
    $compiled = Invoke-Quiet -File $javac -Arguments @("@$argumentFile") -WallSeconds 300
    if ($compiled.ExitCode -ne 0) { throw "javac failed:`n$($compiled.Output)" }
    $seeded = Invoke-Quiet -File $javaCommand.Source -WallSeconds 120 -Arguments @(
        '-cp', $script:classes, "$package.ParserFuzzSeeds", (Join-Path $script:work 'corpus'), $capturedManifest)
    if ($seeded.ExitCode -ne 0) { throw "The seed builder failed:`n$($seeded.Output)" }
    New-Item -ItemType Directory -Force -Path $script:caseTemp | Out-Null

    # For the run records.
    $script:jdkVersion = Get-ToolVersion -File $javaCommand.Source -Arguments @('-version') -Pattern 'version "([^"]+)"'
    $script:jazzerVersion = Get-ToolVersion -File $script:jazzerExe -Arguments @('--version') -Pattern '(?m)^Jazzer v?(\S+)'
    $script:commit = Get-RootCommit

    Invoke-HeavyJob -Label 'parser fuzzing' -ScriptBlock {
        $ErrorActionPreference = 'Stop'
        $global:LASTEXITCODE = 2
        # A wait for a slot can carry the start out of the hours the run may take.
        $late = Get-WindowRefusal -PlannedSeconds $plannedSeconds
        if ($late) {
            Write-Host "[fuzz] $late Nothing ran."
            $global:LASTEXITCODE = 3
            return
        }
        # The planted bug first: a clean run means nothing from a harness that can't find one.
        $planted = @(Invoke-Target -Name 'faulty' -Seconds $SelfCheckSeconds -Findings (Join-Path $script:work 'self-check') -SelfCheck)
        if (@($planted | Where-Object { $_.Name -like 'crash-*' }).Count -eq 0) {
            Write-Host "[fuzz] Jazzer didn't find FaultyBoxFuzz's planted bug in $SelfCheckSeconds s, so a clean run proves nothing. Stopping."
            return
        }
        Write-Host '[fuzz] the planted bug was found, so the harness reports what it finds'
        if ($SelfCheckOnly) { $global:LASTEXITCODE = 0; return }
        $all = @()
        foreach ($name in $chosen) { $all += @(Invoke-Target -Name $name -Seconds $Seconds -Findings (Join-Path $FindingsPath $name)) }
        if ($all.Count -gt 0) {
            Write-Host "[fuzz] $($all.Count) finding(s) under $FindingsPath. Fix the parser, and copy each minimized-* file into tools/fuzz/regressions/<target>/ so ParserFuzzSeedsTest replays it."
            $global:LASTEXITCODE = 1
        } else {
            Write-Host "[fuzz] no findings in $Seconds s a target ($($chosen -join ', ')). The records are in $($script:runsRoot)."
            Remove-Item -LiteralPath $FindingsPath -Recurse -Force -ErrorAction SilentlyContinue
            $global:LASTEXITCODE = 0
        }
    }
    $exitCode = $LASTEXITCODE
} catch {
    # A harness failure is 2, never the 1 of a finding.
    $host.UI.WriteErrorLine("[fuzz] the harness failed: $($_.Exception.Message)")
    $exitCode = 2
} finally {
    if ($script:work) { Remove-Item -LiteralPath $script:work -Recurse -Force -ErrorAction SilentlyContinue }
    $script:sha1.Dispose()
}
exit $exitCode
