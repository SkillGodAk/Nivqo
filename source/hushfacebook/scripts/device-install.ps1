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
