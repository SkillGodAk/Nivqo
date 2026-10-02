param(
    [string]$Version = "0.1.0",
    [string]$OutputPath,
    [string]$AndroidSdk = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$facebook = Join-Path $root "source\hushfacebook"
$messenger = Join-Path $root "source\hushmessenger"

if (-not $OutputPath) {
    $OutputPath = Join-Path $root "updates\bundles\nivqo-patches.mpp"
}

if (-not $AndroidSdk) {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        $AndroidSdk = $env:ANDROID_HOME
    } elseif (Test-Path "C:\AndroidSdkShared") {
        $AndroidSdk = "C:\AndroidSdkShared"
    } else {
        throw "Android SDK not found. Pass -AndroidSdk or set ANDROID_HOME."
    }
}

foreach ($required in @($facebook, $messenger)) {
    if (-not (Test-Path $required)) {
        throw "Missing source tree: $required"
    }
}

$temp = Join-Path $env:TEMP ("nivqo-combined-build-" + $PID)

function Copy-Tree {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [string[]]$ExcludeDirectories = @(),
        [string[]]$ExcludeFiles = @()
    )

    $copyArgs = @($Source, $Destination, "/E", "/NFL", "/NDL", "/NJH", "/NJS", "/NP")
    if ($ExcludeDirectories.Count -gt 0) {
        $copyArgs += "/XD"
        $copyArgs += $ExcludeDirectories
    }
    if ($ExcludeFiles.Count -gt 0) {
        $copyArgs += "/XF"
        $copyArgs += $ExcludeFiles
    }

    & robocopy @copyArgs | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "robocopy failed (${LASTEXITCODE}): $Source -> $Destination"
    }
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New
    )

    $sourceText = [System.IO.File]::ReadAllText($Path)
    $first = $sourceText.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "Expected text not found in $Path : $Old"
    }
    $second = $sourceText.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "Expected text matched more than once in $Path : $Old"
    }
    $sourceText = $sourceText.Remove($first, $Old.Length).Insert($first, $New)
    [System.IO.File]::WriteAllText($Path, $sourceText, [System.Text.UTF8Encoding]::new($false))
}

try {
    if (Test-Path $temp) {
        Remove-Item -Recurse -Force $temp
    }
    New-Item -ItemType Directory -Path $temp | Out-Null

    Copy-Tree -Source $facebook -Destination $temp -ExcludeDirectories @(".git", "build", ".gradle", ".kotlin", ".cxx") -ExcludeFiles @("local.properties")
    Copy-Tree -Source (Join-Path $messenger "patches\src\main") -Destination (Join-Path $temp "patches\src\main")
    Copy-Tree -Source (Join-Path $messenger "extensions\messenger") -Destination (Join-Path $temp "extensions\messenger") -ExcludeDirectories @("build", ".gradle")

    [System.IO.File]::WriteAllText((Join-Path $temp "local.properties"), ("sdk.dir=" + $AndroidSdk.Replace("\", "\\")), [System.Text.Encoding]::ASCII)

    $gradleProperties = Join-Path $temp "gradle.properties"
    $properties = [System.IO.File]::ReadAllText($gradleProperties)
    $properties = [regex]::Replace($properties, '(?m)^version\s*=\s*.*$', "version = $Version", 1)
    [System.IO.File]::WriteAllText($gradleProperties, $properties, [System.Text.UTF8Encoding]::new($false))

    $patchBuild = Join-Path $temp "patches\build.gradle.kts"
    Replace-ExactlyOnce $patchBuild 'name = "Hushfacebook"' 'name = "Nivqo Patches"'
    Replace-ExactlyOnce $patchBuild 'description = "Hushfacebook patches for the Facebook app, built for Morphe. Sponsored posts, Reels, Stories and suggested clutter out of your feed, and more control over what Facebook shows you."' 'description = "Nivqo patch bundle for Facebook and Messenger, combining the current HushFacebook and HushMessenger cores with Nivqo Traditional Chinese support."'
    Replace-ExactlyOnce $patchBuild 'source = "https://github.com/SysAdminDoc/Hushfacebook"' 'source = "https://github.com/SkillGodAk/Nivqo"'
    Replace-ExactlyOnce $patchBuild 'author = "SysAdminDoc"' 'author = "SkillGodAk"'
    Replace-ExactlyOnce $patchBuild 'contact = "https://github.com/SysAdminDoc/Hushfacebook/issues"' 'contact = "https://github.com/SkillGodAk/Nivqo/issues"'
    Replace-ExactlyOnce $patchBuild 'website = "https://github.com/SysAdminDoc/Hushfacebook"' 'website = "https://github.com/SkillGodAk/Nivqo"'

    Replace-ExactlyOnce (Join-Path $temp "patches\src\main\kotlin\app\hushmessenger\patches\controls\MaterialYouPatch.kt") 'name = "Material You theme"' 'name = "Messenger · Material You theme"'
    Replace-ExactlyOnce (Join-Path $temp "patches\src\main\kotlin\app\hushmessenger\patches\coexist\RestoreTrustPatch.kt") 'name = "Restore screens on re-signed builds"' 'name = "Messenger · Restore screens on re-signed builds"'
    Replace-ExactlyOnce (Join-Path $temp "patches\src\main\kotlin\app\hushmessenger\patches\controls\MessengerControlsPatch.kt") 'name = "View stories anonymously"' 'name = "Messenger · View stories anonymously"'

    Push-Location $temp
    try {
        & .\gradlew.bat :patches:generatePatchesList :patches:buildAndroid -PallowMavenLocal=true --dependency-verification off --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Combined MPP Gradle build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    $catalogPath = Join-Path $temp "patches-list.json"
    $catalog = Get-Content $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $patches = @($catalog.patches)

    $facebookCount = 0
    $messengerCount = 0
    $otherCount = 0
    foreach ($patch in $patches) {
        $packages = @($patch.compatiblePackages.PSObject.Properties.Name)
        if ($packages -contains "com.facebook.katana") {
            $facebookCount++
        } elseif ($packages -contains "com.facebook.orca") {
            $messengerCount++
        } else {
            $otherCount++
        }
    }

    $duplicates = @($patches | Group-Object name | Where-Object { $_.Count -gt 1 })
    if ($patches.Count -ne 90 -or $facebookCount -ne 59 -or $messengerCount -ne 31 -or $otherCount -ne 0) {
        throw "Unexpected catalog split: total=$($patches.Count), Facebook=$facebookCount, Messenger=$messengerCount, other=$otherCount"
    }
    if ($duplicates.Count -ne 0) {
        throw "Combined catalog still contains duplicate patch names: $($duplicates.Name -join ', ')"
    }

    $built = Join-Path $temp ("patches\build\release\patches-" + $Version + ".mpp")
    if (-not (Test-Path $built)) {
        throw "Expected bundle not found: $built"
    }

    $verifyDir = Join-Path $temp "verify"
    New-Item -ItemType Directory -Path $verifyDir -Force | Out-Null
    tar -xf $built -C $verifyDir "META-INF/MANIFEST.MF"
    $manifest = Get-Content (Join-Path $verifyDir "META-INF\MANIFEST.MF") -Raw -Encoding UTF8
    if ($manifest -notmatch '(?m)^Name: Nivqo Patches\s*$') {
        throw "Bundle manifest does not contain Name: Nivqo Patches"
    }
    if ($manifest -notmatch ("(?m)^Version: " + [regex]::Escape($Version) + "\s*$")) {
        throw "Bundle manifest version does not match $Version"
    }

    New-Item -ItemType Directory -Path (Split-Path $OutputPath -Parent) -Force | Out-Null
    Copy-Item $built $OutputPath -Force

    $sha = (Get-FileHash $OutputPath -Algorithm SHA256).Hash
    Write-Output "Nivqo combined MPP built successfully."
    Write-Output "Version: $Version"
    Write-Output "Patches: $($patches.Count) (Facebook $facebookCount + Messenger $messengerCount)"
    Write-Output "Output: $OutputPath"
    Write-Output "SHA-256: $sha"
} finally {
    if (Test-Path $temp) {
        Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    }
}