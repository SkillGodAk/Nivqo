<#
.SYNOPSIS
    Start or carry on the GitLab coverage ledger, check it, or check the sentinel projects.

.DESCRIPTION
    A prototype of the scan a census of GitLab would take, now that GitLab.com has no global code
    search. scripts/gitlab-coverage.ps1 holds the ledger and its rules; this runs them.

    With no switch it starts the ledger, or carries on the one that's there, for at most
    -MaxRequests requests, then says what the ledger covers and lists what's still open. Run it
    again to carry on. A new ledger covers every public project from the first id to the highest
    one GitLab has when it starts, unless -StartAfter and -Ceiling name a smaller range.

    -CheckOnly reads the ledger and nothing else. -Sentinels reads each sentinel project into a
    one-project ledger of its own and checks every record of it a second way, against GitLab
    asked directly.

    Exit 0 means the ledger is complete for its range, or every sentinel matched. That is not the
    census: scripts/audit-facebook-sources.ps1 still records gitlab-code-search as skipped, and
    nothing here reads or writes sources/facebook-sources.json. A full range read to the end and
    checked is what it would take, and at one anonymous request a second that is a long job this
    script doesn't attempt on its own.

    Requests are anonymous GETs to gitlab.com, one at a time, -DelayMilliseconds apart. A 429 or
    a used-up rate-limit window ends the run, and a run started before the wait GitLab asked for
    is over asks for nothing. That wait is kept in rate-limit-waits.jsonl beside the ledgers, so
    a 429 on the ceiling read or the sentinel check holds every ledger in the directory too. The
    ledgers go under build/gitlab-coverage, which git ignores.

.EXAMPLE
    pwsh -File scripts/scan-gitlab-coverage.ps1 -Sentinels
.EXAMPLE
    pwsh -File scripts/scan-gitlab-coverage.ps1 -StartAfter 0 -Ceiling 600 -MaxRequests 40
.EXAMPLE
    pwsh -File scripts/scan-gitlab-coverage.ps1 -CheckOnly
#>
[CmdletBinding()]
param(
    [string]$Root,
    # Defaults to build/gitlab-coverage under -Root.
    [string]$LedgerDirectory,
    [long]$StartAfter = 0,
    # 0 pins the highest public project id GitLab has when the ledger starts.
    [long]$Ceiling = 0,
    [int]$PerPage = 100,
    # A run stops after this many requests and carries on from the ledger next time.
    [int]$MaxRequests = 60,
    [int]$DelayMilliseconds = 1000,
    [switch]$CheckOnly,
    [switch]$Sentinels
)

$ErrorActionPreference = 'Stop'
# Not a parameter default: Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'gitlab-coverage.ps1')

if ($PSVersionTable.PSVersion.Major -lt 6) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

$rootPath = (Resolve-Path -LiteralPath $Root).Path
if (-not $LedgerDirectory) { $LedgerDirectory = Join-Path (Join-Path $rootPath 'build') 'gitlab-coverage' }
$LedgerDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($LedgerDirectory)
foreach ($guarded in @('patches', 'extensions', 'sources', 'scripts', 'docs')) {
    $guardedPath = [IO.Path]::GetFullPath((Join-Path $rootPath $guarded)).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (([IO.Path]::GetFullPath($LedgerDirectory).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar).StartsWith($guardedPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The coverage ledger can't go under $guarded/, which holds what the repository ships: $LedgerDirectory"
    }
}

function Write-Step {
    param([string]$Message)
    Write-Host "[coverage] $Message"
}

function Write-Stop {
    # Why a run or a check stopped before it had nothing left to do.
    param($Outcome)
    switch ("$($Outcome.Stopped)") {
        'request-budget' { Write-Step "stopped after $($Outcome.Requests) request(s), the budget for one run; run again to carry on" }
        'rate-limited' {
            if ($Outcome.Requests -eq 0) {
                Write-Step "the last run was rate limited (HTTP 429) and $($Outcome.RetryAfter) s of the wait GitLab asked for are left; nothing was requested"
            } else {
                $wait = if ($null -ne $Outcome.RetryAfter) { "GitLab asked for $($Outcome.RetryAfter) s" } else { 'GitLab named no wait' }
                Write-Step "rate limited (HTTP 429) after $($Outcome.Requests) request(s); $wait before the next run"
            }
        }
        'rate-limit-window' { Write-Step "the rate-limit window is used up after $($Outcome.Requests) request(s); it reopens at $($Outcome.WindowReset) (Unix time)" }
        'no-answer' { Write-Step "gitlab.com did not answer after $($Outcome.Requests) request(s); run again when it does" }
        'ceiling-unread' { Write-Step 'the highest public project id could not be read, so no ledger was started' }
    }
}

function Write-Coverage {
    # What the ledger covers, and everything in it that isn't settled.
    param($Coverage, [string]$Path)
    Write-Step "ledger: $Path"
    foreach ($problem in $Coverage.Problems) { Write-Step "problem: $problem" }
    if ($null -ne $Coverage.Ceiling) {
        Write-Step ("projects after $($Coverage.StartAfter) up to $($Coverage.Ceiling): $($Coverage.Pages) page(s), " +
            "$($Coverage.Projects) project(s), $($Coverage.Branches) branch(es) pinned, $($Coverage.Trees) tree(s) pinned")
    }
    $shown = 0
    foreach ($open in $Coverage.Unresolved) {
        $shown++
        if ($shown -gt 40) {
            Write-Step "... and $($Coverage.Unresolved.Count - 40) more unresolved"
            break
        }
        $why = $open.Result
        if ($null -ne $open.Status) { $why += " (HTTP $($open.Status))" }
        if ($open.Detail) { $why += ": $($open.Detail)" }
        Write-Step "unresolved $($open.Kind) $($open.Key): $why"
    }
    if (-not $Coverage.Complete) {
        Write-Step "not complete: $($Coverage.Problems.Count) problem(s), $($Coverage.Unresolved.Count) unresolved"
    } elseif ($Coverage.FullRun) {
        Write-Step 'complete for a full run. The census is not changed by this script and still records gitlab-code-search as skipped.'
    } else {
        Write-Step 'complete for this range, which is not all of GitLab. The census still records gitlab-code-search as skipped.'
    }
}

if ($Sentinels) {
    $matched = $true
    # Each sentinel has a ledger of its own, so a rate limit or a dead line on one has to end the
    # whole check here, or the next sentinel would go on asking.
    $ending = @('rate-limited', 'rate-limit-window', 'no-answer')
    foreach ($sentinel in Get-GitLabCoverageSentinels) {
        # A range of one id, read through the same pages, branch lists and trees a full run uses.
        $path = Join-Path $LedgerDirectory "sentinel-$($sentinel.Id).jsonl"
        $outcome = $null
        if (-not $CheckOnly) {
            $outcome = Invoke-GitLabCoverageRun -Path $path -StartAfter ($sentinel.Id - 1) -Ceiling $sentinel.Id -PerPage 1 `
                -MaxRequests $MaxRequests -DelayMilliseconds $DelayMilliseconds
            Write-Stop $outcome
        }
        $coverage = Test-GitLabCoverage -Path $path
        Write-Coverage $coverage $path
        if ($null -ne $outcome -and ($outcome.WindowUsed -or $ending -contains "$($outcome.Stopped)")) {
            Write-Step 'the sentinel check stops here; run it again later'
            $matched = $false
            break
        }
        if (-not $coverage.Complete) {
            $matched = $false
            continue
        }
        if ($CheckOnly) { continue }
        if ($DelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $DelayMilliseconds }
        $check = Compare-GitLabCoverageSentinel -Path $path -ProjectId $sentinel.Id -Shape $sentinel.Shape -DelayMilliseconds $DelayMilliseconds
        foreach ($difference in @($check.Differences | Select-Object -First 20)) { Write-Step "difference: $difference" }
        if ($check.Differences.Count -gt 20) { Write-Step "... and $($check.Differences.Count - 20) more differences" }
        $verdict = if ($check.Matches) { 'matches GitLab asked directly' } else { "differs in $($check.Differences.Count) place(s)" }
        Write-Step "sentinel $($sentinel.Id) ($($sentinel.Shape)): $verdict, $($check.Compared) value(s) compared in $($check.Requests) request(s)"
        if (-not $check.Matches) { $matched = $false }
        if ($check.WindowUsed -or $ending -contains "$($check.Stopped)") {
            Write-Step 'the sentinel check stops here; run it again later'
            $matched = $false
            break
        }
        if ($DelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $DelayMilliseconds }
    }
    if ($matched) { exit 0 }
    exit 1
}

$ledgerPath = Join-Path $LedgerDirectory 'ledger.jsonl'
if (-not $CheckOnly) {
    $arguments = @{ Path = $ledgerPath; PerPage = $PerPage; MaxRequests = $MaxRequests; DelayMilliseconds = $DelayMilliseconds }
    if ($PSBoundParameters.ContainsKey('StartAfter')) { $arguments.StartAfter = $StartAfter }
    if ($PSBoundParameters.ContainsKey('Ceiling')) { $arguments.Ceiling = $Ceiling }
    $outcome = Invoke-GitLabCoverageRun @arguments
    Write-Stop $outcome
}
$coverage = Test-GitLabCoverage -Path $ledgerPath
Write-Coverage $coverage $ledgerPath
if ($coverage.Complete) { exit 0 }
exit 1
