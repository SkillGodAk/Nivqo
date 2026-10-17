<#
.SYNOPSIS
    Audit a patched Facebook on a phone or emulator: every switch on, a walk through the app, the
    hook report checked against a baseline, and every settings page captured in dark and light.

.DESCRIPTION
    The run, in order:

    1. Optionally installs -Apk over what is there (adb install -r -d, same signing key only).
    2. Creates Hushfacebook's audit folder (Android/data/com.facebook.katana/files/hushfacebook-audit),
       which only adb can make, and pushes a settings profile into it: every switch on except the
       ones that stop a walk (AuditMode.ALL_ON_EXCLUDED), plus -On and less -Off. Facebook applies
       it at its next start and keeps the switches it had in before.json.
    3. Restarts Facebook twice, so every hook starts with the profile's values.
    4. Walks the screens in -Visit with -Swipes swipes each. It only opens links and swipes, so
       nothing is liked, followed or sent.
    5. Asks for the full diagnostic report over an intent and pulls it, then reads [HOOK STATUS]
       with phone-smoke's Read-SmokeReport and compares it to -Expectations: a family that ran in
       the baseline and didn't run now fails, as does anything missing, ambiguous or a crash.
    6. Opens every settings page named in SettingsNavigation.java, scrolls it to the end and
       screenshots each screenful, in dark and light, plus one dark pass per -Locales entry.
       The text of each page goes next to its screenshots, and index.html shows them all.
    7. Puts the switches, the night mode and the app language back, and removes the audit folder.

    The phone has to be unlocked and signed in. -WriteBaseline records which families ran, for
    the next run to be held to: run it once on a build you trust after Facebook's version changes.

.EXAMPLE
    scripts/device-audit.ps1 -Serial emulator-5554 -Apk out/hushfacebook.apk

.EXAMPLE
    scripts/device-audit.ps1 -Serial emulator-5554 -SkipPages -WriteBaseline
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Serial = $env:HUSHFACEBOOK_DEVICE_SERIAL,
    [string]$Apk,
    [string]$OutDir,
    # The walk, step by step. A link is opened and swiped -Swipes times. "tap:<label>" taps the
    # node whose text or description is exactly <label>, from a fresh dump, scrolling back up to
    # find it, and only labels that open or expand something belong here: never Like, Follow,
    # Add friend, Send or a person. "longpress" holds the middle of the screen, "back" goes back.
    # No fb://groups or fb://friends: all-on hides those tabs, and their links then land nowhere
    # or on Android's app chooser.
    [string[]]$Visit = @('fb://feed', 'tap:Comment', 'back', 'fb://watch', 'fb://fb_shorts/viewer', 'longpress',
        'fb://notifications', 'fb://marketplace', 'fb://profile', 'fb://search',
        'fb://bookmarks', 'tap:Settings and privacy', 'fb://bookmarks', 'tap:Feeds', 'fb://feed'),
    [int]$Swipes = 4,
    [int]$SettleSeconds = 4,
    # Setting keys to turn on or off on top of the all-on profile.
    [string[]]$On = @(),
    [string[]]$Off = @(),
    [string]$Expectations = (Join-Path $PSScriptRoot 'device-audit-expectations.json'),
    [switch]$WriteBaseline,
    [switch]$SkipWalk,
    [switch]$SkipPages,
    # App languages for an extra dark pass over the pages each, like 'de' or 'pt-BR'.
    [string[]]$Locales = @(),
    [int]$MaxScrolls = 14,
    # Leaves the profile's switches on and the audit folder in place, for poking at by hand.
    [switch]$KeepSettings
)

$ErrorActionPreference = 'Stop'
# Read-SmokeReport alone: dot-sourcing all of phone-smoke would bind its parameters over these.
$smokeAst = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'phone-smoke.ps1'), [ref]$null, [ref]$null)
$smokeReader = $smokeAst.Find({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Read-SmokeReport' }, $true)
. ([scriptblock]::Create($smokeReader.Extent.Text))
. (Join-Path $PSScriptRoot 'device-install.ps1')

$Package = 'com.facebook.katana'
$Entry = "$Package/.LoginActivity"
$Extra = 'app.morphe.extension.facebook'
$AuditDir = "/sdcard/Android/data/$Package/files/hushfacebook-audit"

if (-not $Serial) { throw 'Pass -Serial or set HUSHFACEBOOK_DEVICE_SERIAL.' }
if (-not $OutDir) {
    $OutDir = Join-Path ([IO.Path]::GetTempPath()) ("hushfacebook-audit-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Force $OutDir | Out-Null

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
    if ($adb -or -not $root) { continue }
    $candidate = Join-Path $root 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { $adb = $candidate }
}
if (-not $adb) { throw 'No adb found. Put it on the PATH or set ANDROID_HOME.' }

function Invoke-Adb {
    $arguments = $args
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $adb -s $Serial @arguments 2>&1 | ForEach-Object { [string]$_ })
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($code -ne 0) { throw "adb $($arguments -join ' ') exited with ${code}: $($output -join ' ')" }
    return $output
}

function Write-Step([string]$Text) { Write-Host "[audit] $Text" }

# One argument for the phone's shell, quoted so spaces and apostrophes survive it.
function Format-Shell([string]$Text) { "'" + $Text.Replace("'", "'\''") + "'" }

function Get-UiNodes {
    for ($try = 1; $try -le 4; $try++) {
        $dump = (Invoke-Adb shell 'rm -f /sdcard/hushfacebook-audit.xml; uiautomator dump /sdcard/hushfacebook-audit.xml >/dev/null 2>&1; cat /sdcard/hushfacebook-audit.xml; rm -f /sdcard/hushfacebook-audit.xml') -join "`n"
        $start = $dump.IndexOf('<?xml')
        if ($start -ge 0) {
            try { return ([xml]$dump.Substring($start)).SelectNodes('//node') } catch { }
        }
        Start-Sleep -Milliseconds 800
    }
    return @()
}

function Get-ScreenText {
    $lines = foreach ($node in Get-UiNodes) {
        foreach ($value in @($node.text, $node.'content-desc')) { if ($value) { $value } }
    }
    return @($lines)
}

function Save-Screenshot([string]$Path) {
    Invoke-Adb shell screencap -p /sdcard/hushfacebook-audit.png | Out-Null
    Invoke-Adb pull /sdcard/hushfacebook-audit.png $Path | Out-Null
    Invoke-Adb shell rm -f /sdcard/hushfacebook-audit.png | Out-Null
}

function Start-Facebook {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Invoke-Adb shell am start -W -n $Entry | Out-Null
    Start-Sleep -Seconds ($SettleSeconds * 2)
}

function Send-Profile([string]$Json) {
    $local = Join-Path $OutDir 'settings.json'
    [IO.File]::WriteAllText($local, $Json)
    Invoke-Adb push $local "$AuditDir/settings.json" | Out-Null
    Invoke-Adb shell rm -f "$AuditDir/import.txt" | Out-Null
    Start-Facebook
    $import = ''
    for ($wait = 0; $wait -lt 10 -and -not $import; $wait++) {
        $import = ((Invoke-Adb shell "cat $AuditDir/import.txt 2>/dev/null; true") -join "`n").Trim()
        if (-not $import) { Start-Sleep -Seconds 1 }
    }
    if (-not $import) { throw 'Facebook never read the settings file. Is this a build with the audit folder in it?' }
    return $import
}

function Get-Report([string]$Path) {
    Invoke-Adb shell rm -f "$AuditDir/report.txt" | Out-Null
    Invoke-Adb shell am start -W -n $Entry --ez "$Extra.AUDIT_REPORT" true | Out-Null
    for ($wait = 0; $wait -lt 20; $wait++) {
        Start-Sleep -Seconds 1
        $there = (Invoke-Adb shell "ls $AuditDir/report.txt 2>/dev/null; true") -join ''
        if ($there -like '*report.txt') {
            Invoke-Adb pull "$AuditDir/report.txt" $Path | Out-Null
            return [IO.File]::ReadAllText($Path)
        }
    }
    throw 'Facebook wrote no report.txt.'
}

# The settings pages, as SettingsNavigation names them.
function Get-SettingsPages {
    $source = Join-Path $PSScriptRoot '..\extensions\facebook\src\main\java\app\morphe\extension\facebook\settings\SettingsNavigation.java'
    $pages = [regex]::Matches([IO.File]::ReadAllText($source), 'section\("([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
    if (-not $pages) { throw "No settings pages found in $source" }
    # "" is the first page and "more" the list of the secondary pages.
    return @('', 'more') + @($pages)
}

function Close-Settings {
    for ($i = 0; $i -lt 3; $i++) {
        $text = Get-ScreenText
        if (-not ($text -contains 'Hushfacebook' -or $text -contains 'Search settings')) { return }
        Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Milliseconds 900
    }
}

function Save-SettingsPages([string]$Pass) {
    $dir = Join-Path $OutDir "pages-$Pass"
    New-Item -ItemType Directory -Force $dir | Out-Null
    $results = New-Object System.Collections.Generic.List[object]
    foreach ($page in Get-SettingsPages) {
        $name = if ($page) { ($page -replace "[^A-Za-z0-9]+", '-').Trim('-').ToLowerInvariant() } else { 'home' }
        Close-Settings
        $command = "am start -W -n $Entry --ez $Extra.OPEN_SETTINGS true"
        if ($page) { $command += " --es $Extra.AUDIT_PAGE $(Format-Shell $page)" }
        Invoke-Adb shell $command | Out-Null
        Start-Sleep -Seconds $SettleSeconds
        $allText = New-Object System.Collections.Generic.List[string]
        $last = $null
        $shots = 0
        for ($scroll = 0; $scroll -le $MaxScrolls; $scroll++) {
            $text = Get-ScreenText
            $signature = $text -join "`n"
            if ($signature -eq $last) { break }
            $last = $signature
            foreach ($line in $text) { if (-not $allText.Contains($line)) { $allText.Add($line) } }
            Save-Screenshot (Join-Path $dir ('{0}-{1:00}.png' -f $name, $scroll))
            $shots++
            Invoke-Adb shell input swipe $swipeX $swipeFrom $swipeX $swipeTo 500 | Out-Null
            Start-Sleep -Milliseconds 900
        }
        [IO.File]::WriteAllLines((Join-Path $dir "$name.txt"), [string[]]$allText)
        $title = if ($page -eq 'more') { 'More settings' } else { $page }
        $opened = (-not $page) -or $allText.Contains($title) -or $Pass -notmatch '^(dark|light)$'
        $results.Add([pscustomobject]@{ Page = $(if ($page) { $page } else { 'Home' }); Name = $name; Shots = $shots; Opened = $opened })
        Write-Step ("{0} {1}: {2} screen(s){3}" -f $Pass, $(if ($page) { $page } else { 'Home' }), $shots, $(if ($opened) { '' } else { ', NOT OPENED' }))
    }
    Close-Settings
    return $results
}

# --- Checks before anything changes ---------------------------------------------------------

if (((Invoke-Adb get-state) -join '') -ne 'device') { throw "$Serial is not ready." }
if (@(Get-AndroidPackagePaths -Adb $adb -Serial $Serial -PackageName $Package).Count -eq 0) { throw "$Package is not installed on $Serial." }
Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
$keyguard = (Invoke-Adb shell dumpsys window) -join "`n"
if ($keyguard -match 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true') {
    throw "$Serial is locked. Unlock it and run this again."
}
$size = (Invoke-Adb shell wm size) -join ' '
if ($size -notmatch '(\d+)x(\d+)\s*$') { throw "Could not read the screen size: $size" }
$width = [int]$Matches[1]; $height = [int]$Matches[2]
$swipeX = [int]($width / 2); $swipeFrom = [int]($height * 0.75); $swipeTo = [int]($height * 0.3)
$nightBefore = ((Invoke-Adb shell cmd uimode night) -join '') -replace '^Night mode:\s*', ''

if ($Apk) {
    Write-Step "installing $(Split-Path -Leaf $Apk)"
    $install = (Invoke-Adb install -r -d -g $Apk) -join ' '
    if ($install -notmatch 'Success') { throw "adb install failed: $install" }
}
$version = ((Invoke-Adb shell dumpsys package $Package) | Where-Object { $_ -match '^\s*versionName=' } | Select-Object -First 1) -replace '^\s*versionName=', ''
Write-Step "Facebook $version on $Serial, results in $OutDir"

$problems = New-Object System.Collections.Generic.List[string]
$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("Facebook $version on $Serial, $(Get-Date -Format 'yyyy-MM-dd HH:mm')")

try {
    # --- Every switch on ----------------------------------------------------------------------
    Invoke-Adb shell mkdir -p $AuditDir | Out-Null
    # A before.json already there is from a run that never put things back, and still holds the
    # switches from before it, so it stays.
    Invoke-Adb shell rm -f "$AuditDir/report.txt" | Out-Null
    $allOn = [ordered]@{ format = 'hushfacebook-audit'; profile = 'all-on'; on = @($On); off = @($Off) }
    $import = Send-Profile ($allOn | ConvertTo-Json -Compress)
    Write-Step "profile: $import"
    $summary.Add("Profile all-on: $import")
    if ($import -notlike 'applied*') { $problems.Add("The profile was not applied: $import") }
    Invoke-Adb pull "$AuditDir/before.json" (Join-Path $OutDir 'before.json') | Out-Null
    $startedEpoch = [long]((Invoke-Adb shell date +%s) -join '').Trim()
    Start-Facebook

    # --- The walk -----------------------------------------------------------------------------
    if (-not $SkipWalk) {
        $stepNumber = 0
        foreach ($step in $Visit) {
            $stepNumber++
            Write-Step $step
            if ($step -like 'tap:*') {
                $label = $step.Substring(4)
                $node = $null
                for ($look = 0; $look -le 5 -and -not $node; $look++) {
                    if ($look -gt 0) {
                        Invoke-Adb shell input swipe $swipeX $swipeTo $swipeX $swipeFrom 300 | Out-Null
                        Start-Sleep -Milliseconds 1200
                    }
                    $node = Get-UiNodes | Where-Object { $_.text -eq $label -or $_.'content-desc' -eq $label } |
                        Where-Object { $_.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' } | Select-Object -First 1
                }
                if ($node -and $node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                    Invoke-Adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)) | Out-Null
                    Start-Sleep -Seconds $SettleSeconds
                } else {
                    $summary.Add("Walk: no '$label' to tap at step $stepNumber.")
                }
            } elseif ($step -eq 'longpress') {
                Invoke-Adb shell input swipe $swipeX ([int]($height / 2)) $swipeX ([int]($height / 2)) 2500 | Out-Null
                Start-Sleep -Seconds 1
            } elseif ($step -eq 'back') {
                Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
                Start-Sleep -Seconds 1
            } else {
                Invoke-Adb shell am start -W -a android.intent.action.VIEW -d $step -p $Package | Out-Null
                Start-Sleep -Seconds $SettleSeconds
                # Before the swipes too, while a tab bar that slides away is still on screen.
                Save-Screenshot (Join-Path $OutDir ('walk-{0:00}-{1}-top.png' -f $stepNumber, ($step -replace '[^A-Za-z0-9]+', '-').Trim('-')))
                for ($i = 0; $i -lt $Swipes; $i++) {
                    Invoke-Adb shell input swipe $swipeX $swipeFrom $swipeX $swipeTo 300 | Out-Null
                    Start-Sleep -Milliseconds 1500
                }
            }
            $shot = ($step -replace '[^A-Za-z0-9]+', '-').Trim('-')
            Save-Screenshot (Join-Path $OutDir ('walk-{0:00}-{1}.png' -f $stepNumber, $shot))
        }

        $reportPath = Join-Path $OutDir 'report.txt'
        $text = Get-Report $reportPath
        $walkStarted = [DateTime]::SpecifyKind([DateTime]'1970-01-01', 'Utc').AddSeconds($startedEpoch)
        $result = Read-SmokeReport -Text $text -Since $walkStarted
        foreach ($problem in $result.Problems) { $problems.Add($problem) }
        $ran = @($result.Families | Where-Object { $_.Invoked -gt 0 } | ForEach-Object { $_.Name })
        # The report lists only families that recorded something, so the rest come from FamilyNames.
        $namesSource = Join-Path $PSScriptRoot '..\extensions\facebook\src\main\java\app\morphe\extension\facebook\settings\FamilyNames.java'
        $allFamilies = @([regex]::Matches([IO.File]::ReadAllText($namesSource), 'public static final String \w+ = "([^"]+)"') |
            ForEach-Object { $_.Groups[1].Value })
        $idle = @($allFamilies + @($result.Families | ForEach-Object { $_.Name }) | Sort-Object -Unique |
            Where-Object { $ran -notcontains $_ })
        $summary.Add("$($allFamilies.Count) hook families, $($ran.Count) ran on the walk")

        if (Test-Path -LiteralPath $Expectations) {
            $expected = Get-Content -Raw -LiteralPath $Expectations | ConvertFrom-Json
            foreach ($name in @($expected.ranOnWalk)) {
                if ($ran -notcontains $name) { $problems.Add("$name ran on the baseline's walk ($($expected.facebook)) and didn't now.") }
            }
            $new = @($ran | Where-Object { @($expected.ranOnWalk) -notcontains $_ })
            if ($new) { $summary.Add("Ran now and not in the baseline: $($new -join ', ')") }
        } elseif (-not $WriteBaseline) {
            $summary.Add("No baseline at $Expectations. Run once with -WriteBaseline on a build you trust.")
        }
        if ($WriteBaseline) {
            $baseline = [ordered]@{
                facebook   = $version
                written    = (Get-Date -Format 'yyyy-MM-dd')
                visit      = @($Visit)
                ranOnWalk  = @($ran | Sort-Object)
                notOnWalk  = @($idle | Sort-Object)
            }
            [IO.File]::WriteAllText($Expectations, ($baseline | ConvertTo-Json -Depth 4) + "`n")
            Write-Step "baseline written to $Expectations"
        }
        $summary.Add('Never ran on the walk: ' + $(if ($idle) { $idle -join ', ' } else { 'none' }))

        $crashes = @(Invoke-Adb logcat -b crash -d -v epoch) | Where-Object {
            $_ -match '^\s*(\d+)\.\d+\s' -and [long]$Matches[1] -ge $startedEpoch
        }
        if ($crashes.Count -gt 0) { [IO.File]::WriteAllLines((Join-Path $OutDir 'crash-buffer.txt'), [string[]]$crashes) }
        $fatal = @($crashes | Where-Object { $_ -match "Process: $([regex]::Escape($Package))\b" })
        if ($fatal.Count -gt 0) { $problems.Add("Facebook crashed during the audit: $($fatal[0].Trim())") }
    }

    # --- Every settings page ------------------------------------------------------------------
    if (-not $SkipPages) {
        $passes = New-Object System.Collections.Generic.List[object]
        foreach ($mode in @('yes', 'no')) {
            Invoke-Adb shell cmd uimode night $mode | Out-Null
            Start-Facebook
            $pass = if ($mode -eq 'yes') { 'dark' } else { 'light' }
            $passes.Add([pscustomobject]@{ Name = $pass; Pages = Save-SettingsPages $pass })
        }
        Invoke-Adb shell cmd uimode night yes | Out-Null
        foreach ($locale in $Locales) {
            Invoke-Adb shell cmd locale set-app-locales $Package --locales $locale | Out-Null
            Start-Facebook
            $passes.Add([pscustomobject]@{ Name = "dark-$locale"; Pages = Save-SettingsPages "dark-$locale" })
        }
        if ($Locales) { Invoke-Adb shell cmd locale set-app-locales $Package --locales "''" | Out-Null }

        foreach ($pass in $passes) {
            foreach ($page in $pass.Pages) {
                if (-not $page.Opened) { $problems.Add("$($pass.Name): the $($page.Page) page did not open.") }
            }
        }
        $html = New-Object System.Text.StringBuilder
        [void]$html.Append('<!doctype html><meta charset="utf-8"><title>Hushfacebook audit</title>')
        [void]$html.Append('<style>body{background:#111;color:#ddd;font:14px sans-serif}img{width:240px;margin:2px;border:1px solid #333}h2{margin-top:24px}</style>')
        $walkShots = @(Get-ChildItem -LiteralPath $OutDir -Filter 'walk-*.png' | Sort-Object Name)
        if ($walkShots) {
            [void]$html.Append('<h2>Walk</h2>')
            foreach ($shot in $walkShots) { [void]$html.Append("<img src=`"$($shot.Name)`" title=`"$($shot.Name)`">") }
        }
        foreach ($pass in $passes) {
            foreach ($page in $pass.Pages) {
                [void]$html.Append("<h2>$($pass.Name): $([Net.WebUtility]::HtmlEncode($page.Page))</h2>")
                for ($i = 0; $i -lt $page.Shots; $i++) {
                    [void]$html.Append(('<img src="pages-{0}/{1}-{2:00}.png">' -f $pass.Name, $page.Name, $i))
                }
            }
        }
        [IO.File]::WriteAllText((Join-Path $OutDir 'index.html'), $html.ToString())
        $summary.Add("Settings pages: $(($passes | ForEach-Object { "$($_.Name) $($_.Pages.Count)" }) -join ', '), see index.html")
    }
} finally {
    # --- Put things back ----------------------------------------------------------------------
    try {
        if ($nightBefore) { Invoke-Adb shell cmd uimode night $nightBefore | Out-Null }
        if (-not $KeepSettings -and (Test-Path -LiteralPath (Join-Path $OutDir 'before.json'))) {
            $restore = Send-Profile ([IO.File]::ReadAllText((Join-Path $OutDir 'before.json')))
            Write-Step "switches put back: $restore"
            Invoke-Adb shell rm -rf $AuditDir | Out-Null
            Start-Facebook
        }
    } catch {
        $problems.Add("Could not put the phone back as it was: $($_.Exception.Message)")
    }
}

if ($problems.Count -eq 0) { $summary.Add('PASS') }
else {
    $summary.Add("FAIL ($($problems.Count))")
    foreach ($problem in $problems) { $summary.Add("  $problem") }
}
[IO.File]::WriteAllLines((Join-Path $OutDir 'summary.txt'), [string[]]$summary)
$summary | ForEach-Object { Write-Step $_ }
if ($problems.Count -gt 0) { exit 1 }
