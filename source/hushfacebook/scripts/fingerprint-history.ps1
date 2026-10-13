<#
.SYNOPSIS
    The retired Facebook builds the fingerprint calibration describes, held to their pins.

.DESCRIPTION
    Dot-sourced by fingerprint-candidates.ps1 and test-fingerprint-candidates.ps1, after
    common.ps1, whose Get-BaseApk it uses. fingerprint-history.txt says what each build has to be.
#>

# A folder's path with every junction or symbolic link along it followed, so a link into the
# fixture folder compares as the fixture folder. A part that isn't there is kept as written.
function Resolve-RealFolder {
    param([string]$Path)
    $full = [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $drive = [System.IO.Path]::GetPathRoot($full)
    $real = $drive.TrimEnd('\', '/')
    foreach ($part in $full.Substring($drive.Length).Split([char[]]'\/', [System.StringSplitOptions]::RemoveEmptyEntries)) {
        $next = Join-Path ($real + '\') $part
        $item = Get-Item -LiteralPath $next -Force -ErrorAction SilentlyContinue
        $hops = 0
        while ($item -and $item.LinkType -in 'Junction', 'SymbolicLink' -and $hops++ -lt 32) {
            $target = "$(@($item.Target)[0])" -replace '^\\\?\?\\', ''
            if (-not $target) { break }
            $next = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine((Split-Path -Parent $next), $target))
            $item = Get-Item -LiteralPath $next -Force -ErrorAction SilentlyContinue
        }
        $real = $next.TrimEnd('\', '/')
    }
    return $real
}

function Resolve-FingerprintHistory {
    <#
    .SYNOPSIS
        The retired builds fingerprint-calibration.txt describes, as base APKs copied out to -Work,
        once each bundle in HUSHFACEBOOK_FINGERPRINT_HISTORY_DIR is the one fingerprint-history.txt
        pins.
    .DESCRIPTION
        The fixture folder keeps only the builds the patches target, and the calibration's 577 and
        580 left it. They're still the only real transitions the ranker is held to, so they live in
        a folder of their own that nothing writes, outside the fixture folder and the repository.
        Each bundle has to have its pinned SHA-256, and its base APK the pinned package, version
        name, version code and ABI as aapt2 reads them, and only Meta's signers as apksigner reads
        them. A folder that isn't set, isn't there or holds anything else throws and says what
        differs: a calibration that skipped would read as one that passed.

        Returns Old and New, the two base APKs, with OldVersion and NewVersion.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$Work,
        [string]$Folder = $env:HUSHFACEBOOK_FINGERPRINT_HISTORY_DIR,
        [string]$ManifestPath = (Join-Path $PSScriptRoot 'fingerprint-history.txt')
    )

    $setUp = 'CONTRIBUTING.md says how to set it up once.'
    if (-not $Folder) {
        throw ('HUSHFACEBOOK_FINGERPRINT_HISTORY_DIR is not set. It names the folder holding the retired Facebook ' +
            "builds the fingerprint calibration describes, and the calibration can't run without them. $setUp")
    }
    if (-not (Test-Path -LiteralPath $Folder -PathType Container)) {
        throw "HUSHFACEBOOK_FINGERPRINT_HISTORY_DIR names $Folder, which is not a folder. $setUp"
    }
    $full = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Folder).ProviderPath).TrimEnd('\', '/')
    $spellings = @($full, (Resolve-RealFolder $full)) | Select-Object -Unique
    $fixtures = if ($env:HUSHFACEBOOK_FIXTURE_DIR) { $env:HUSHFACEBOOK_FIXTURE_DIR } else { Join-Path $Root 'fixtures' }
    foreach ($other in @(@{ Path = $fixtures; Name = 'the fixture folder' }, @{ Path = $Root; Name = 'the repository' })) {
        $otherFull = [System.IO.Path]::GetFullPath(
            $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($other.Path)).TrimEnd('\', '/')
        foreach ($history in $spellings) {
            foreach ($otherSpelling in @($otherFull, (Resolve-RealFolder $otherFull)) | Select-Object -Unique) {
                $inside = ($history + '\').StartsWith($otherSpelling + '\', [System.StringComparison]::OrdinalIgnoreCase)
                $around = ($otherSpelling + '\').StartsWith($history + '\', [System.StringComparison]::OrdinalIgnoreCase)
                if ($inside -or $around) {
                    throw ("HUSHFACEBOOK_FINGERPRINT_HISTORY_DIR names $full, which overlaps $($other.Name) ($otherFull). " +
                        "It has to be a folder of its own. $setUp")
                }
            }
        }
    }

    $signers = @()
    $builds = @{}
    foreach ($line in Get-Content -LiteralPath $ManifestPath) {
        $fields = @($line.Trim() -split '\s+' | Where-Object { $_ })
        if ($fields.Count -eq 0 -or $fields[0].StartsWith('#')) { continue }
        if ($fields[0] -eq 'signer' -and $fields.Count -eq 2) { $signers += $fields[1].ToLowerInvariant(); continue }
        if ($fields[0] -in 'old', 'new' -and $fields.Count -eq 7 -and -not $builds.ContainsKey($fields[0])) {
            $builds[$fields[0]] = [pscustomobject]@{ File = $fields[1]; Sha256 = $fields[2].ToLowerInvariant()
                Package = $fields[3]; Version = $fields[4]; VersionCode = $fields[5]; Abi = $fields[6] }
            continue
        }
        throw "$ManifestPath has a line it can't read: $line"
    }
    if ($signers.Count -eq 0 -or -not $builds.ContainsKey('old') -or -not $builds.ContainsKey('new')) {
        throw "$ManifestPath needs a signer, an old build and a new build."
    }
    $apksigner = Join-Path (Split-Path -Parent $Aapt2) 'apksigner.bat'
    if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw "No apksigner beside aapt2: $apksigner" }

    New-Item -ItemType Directory -Force -Path $Work | Out-Null
    $bases = @{}
    foreach ($role in 'old', 'new') {
        $build = $builds[$role]
        $path = Join-Path $full $build.File
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "The fingerprint history in $full is missing $($build.File), the $role build the calibration describes. $setUp"
        }
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne $build.Sha256) {
            throw "$($build.File) in $full has SHA-256 $hash, not the pinned $($build.Sha256), so it isn't the build the calibration describes."
        }
        $base = Get-BaseApk -Apk $path -Destination (Join-Path $Work "history-$role-base.apk")
        # Native calls with Continue: Windows PowerShell 5.1 turns a program's stderr into a
        # terminating error under Stop.
        $badging = & { $ErrorActionPreference = 'Continue'; @(& $Aapt2 dump badging $base 2>&1 | ForEach-Object { "$_" }) }
        if ($LASTEXITCODE -ne 0) { throw "aapt2 could not read the base APK of $($build.File): $($badging -join ' ')" }
        $package = @($badging | Where-Object { $_ -match "^package: name='[^']*' versionCode='[^']*' versionName='[^']*'" })
        if ($package.Count -ne 1) { throw "aapt2 printed no package line for the base APK of $($build.File)." }
        $null = $package[0] -match "^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'"
        $read = "$($Matches[1]) $($Matches[3]) ($($Matches[2]))"
        $pinned = "$($build.Package) $($build.Version) ($($build.VersionCode))"
        if ($read -ne $pinned) { throw "The base APK of $($build.File) is $read, not the pinned $pinned." }
        $abis = @($badging | Where-Object { $_ -like 'native-code:*' } |
            ForEach-Object { [regex]::Matches($_, "'([^']*)'") | ForEach-Object { $_.Groups[1].Value } })
        if (($abis -join ' ') -ne $build.Abi) {
            throw "The base APK of $($build.File) carries native code for '$($abis -join ' ')', not the pinned $($build.Abi)."
        }
        $certificates = & { $ErrorActionPreference = 'Continue'; @(& $apksigner verify --print-certs $base 2>&1 | ForEach-Object { "$_" }) }
        if ($LASTEXITCODE -ne 0) { throw "apksigner could not verify the base APK of $($build.File): $($certificates -join ' ')" }
        # A Source Stamp is Play's mark on the APK, not a signer of the app.
        $digests = @($certificates | Where-Object { $_ -notmatch '^Source Stamp' -and $_ -match 'certificate SHA-256 digest: [0-9a-f]{64}' } |
            ForEach-Object { ($_ -replace '^.*certificate SHA-256 digest: ', '').Trim() } | Sort-Object -Unique)
        if ($digests.Count -eq 0 -or @($digests | Where-Object { $signers -notcontains $_ }).Count -gt 0) {
            $by = if ($digests.Count) { $digests -join ', ' } else { 'no signer apksigner names' }
            throw "The base APK of $($build.File) is signed by $by, not by Meta alone."
        }
        $bases[$role] = $base
    }
    return [pscustomobject]@{ Old = $bases['old']; New = $bases['new']
        OldVersion = $builds['old'].Version; NewVersion = $builds['new'].Version }
}
