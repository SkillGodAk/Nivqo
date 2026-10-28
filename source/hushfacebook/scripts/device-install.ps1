<#
.SYNOPSIS
    Shared guarded ADB operations for a replacement install.
#>

function Get-AndroidPackagePaths {
    <#
    .SYNOPSIS
        The package's APK paths on the phone, or none when it isn't installed.
    .DESCRIPTION
        pm path answers a package that isn't installed with exit 1 and prints nothing (seen on
        Android 16, 2026-10-12). Any other failure, such as adb's own "device not found" on exit 1,
        throws rather than reading as not installed.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $pathOutput = @(& $Adb -s $Serial shell pm path $PackageName 2>&1 | ForEach-Object { ([string]$_).Trim() })
        $pathExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    $detail = ($pathOutput -join ' ').Trim()
    if ($pathExitCode -eq 1 -and -not $detail) { return @() }
    if ($pathExitCode -ne 0) {
        if (-not $detail) { $detail = "exit $pathExitCode" }
        throw "adb could not check $PackageName on ${Serial}: $detail"
    }
    return @($pathOutput | Where-Object { $_.StartsWith('package:', [System.StringComparison]::Ordinal) })
}

function Remove-AndroidPackageIfInstalled {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $installedPaths = @(Get-AndroidPackagePaths -Adb $Adb -Serial $Serial -PackageName $PackageName)
    if ($installedPaths.Count -eq 0) {
        Write-Host "[device] $PackageName is not installed on $Serial; skipping uninstall"
        return $false
    }

    Write-Host "[device] uninstalling $PackageName on $Serial"
    & $Adb -s $Serial uninstall $PackageName | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "adb uninstall failed on $Serial." }
    return $true
}

function Select-AndroidLauncher {
    <#
    .SYNOPSIS
        The one enabled launcher entry in `cmd package query-activities --brief` output.
    .DESCRIPTION
        Picking an alternate app icon turns the default launcher entry off and one of its aliases
        on, and Android starts nothing for a component that is off. Android lists only enabled
        components, so the single LauncherClass or LauncherClass.<alias> line is the one to start.
        None or several throw, so a caller can stop before it changes anything. Reads text only.
    #>
    [CmdletBinding()]
    param(
        [AllowEmptyCollection()][AllowNull()][string[]]$Lines,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$LauncherClass
    )

    $found = New-Object System.Collections.Generic.List[string]
    foreach ($line in @($Lines)) {
        $match = [regex]::Match(([string]$line).Trim(), '\A' + [regex]::Escape($PackageName) + '/([A-Za-z0-9_.$]+)\z')
        if (-not $match.Success) { continue }
        $class = $match.Groups[1].Value
        if ($class.StartsWith('.', [StringComparison]::Ordinal)) { $class = $PackageName + $class }
        if ($class -ceq $LauncherClass -or $class.StartsWith("$LauncherClass.", [StringComparison]::Ordinal)) {
            if (-not $found.Contains($class)) { $found.Add($class) }
        }
    }
    if ($found.Count -eq 0) { throw "$PackageName lists no enabled launcher entry. No device changes were made." }
    if ($found.Count -gt 1) { throw "$PackageName lists several launcher entries ($($found -join ', ')). No device changes were made." }
    return $found[0]
}
