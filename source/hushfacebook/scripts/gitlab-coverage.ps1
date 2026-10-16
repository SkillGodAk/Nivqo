<#
.SYNOPSIS
    A coverage ledger for a census of GitLab's public projects: what was listed, which branch
    commits and trees were read, what couldn't be, and whether that adds up to complete coverage.

.DESCRIPTION
    Dot-source this. GitLab.com has no global code search, so scripts/audit-facebook-sources.ps1
    records gitlab-code-search as skipped and never stamps a census on it. The only other way to
    know what GitLab's public projects hold is to list every one of them and read every branch's
    tree. This file is the bookkeeping for that, as a prototype. It doesn't scan all of GitLab,
    and nothing here reads or writes sources/facebook-sources.json: the census keeps its skip and
    its date until a full run of this ledger is complete and has been checked.

    The ledger is JSON Lines, one record a line, only ever appended to:

    - run: the range the ledger covers. The highest public project id is read once and pinned as
      the ceiling. A later run never moves it, so projects created during a scan don't count.
    - page: one page of the keyset project list, ids ascending from the cursor it was asked at.
      Each page has to start after the cursor, and the next is asked at the last id this one
      served. The list is finished once a page reaches the ceiling.
    - project: every branch a project declares, each at its commit, or why they couldn't be read.
    - tree: every path and blob id in the tree of one of those commits. A tree is asked for by
      commit, never by branch name, so a push during the scan can't change what was read.

    Each record carries a result. read, pinned and empty settle it. Anything else (rate-limited,
    http-failure with its status, inaccessible, incomplete, too-large, truncated, repeated-page,
    link-mismatch, ended-early, malformed) is unresolved. It stays in the ledger and the next run
    tries it again, appending the new attempt after it. Test-GitLabCoverage reads the ledger alone
    and says whether every page, project and tree up to the ceiling is settled. A page out of
    sequence, a record for something already settled, a line that isn't a record and anything
    unresolved or not read yet each make it say no, and it lists them.

    Requests are anonymous GETs to gitlab.com, one at a time. A 429 is recorded and ends the run,
    and so does a rate-limit window with nothing left in it. A run started before the wait a 429
    asked for is over asks for nothing. No token is ever sent.
#>

function Get-GitLabCoverageApi {
    return 'https://gitlab.com/api/v4'
}

function Get-GitLabCoverageSettled {
    # The results that settle a record of each kind. Every other result is unresolved.
    return @{ page = @('read'); project = @('pinned', 'empty'); tree = @('pinned') }
}

function Get-GitLabCoverageSentinels {
    <#
        A few old, quiet public projects whose ledger records are checked a second way
        (Compare-GitLabCoverageSentinel): one with no branches, one with several, and one whose
        tree takes more than one page. A sentinel that loses its shape fails the check, and
        another is picked.
    #>
    return @(
        [pscustomobject]@{ Id = 3905; Shape = 'empty' }
        [pscustomobject]@{ Id = 4549; Shape = 'multi-branch' }
        [pscustomobject]@{ Id = 3918; Shape = 'large-tree' }
    )
}

function Test-GitLabCoverageId {
    # A project id or cursor as the ledger holds one: a whole number, zero or above.
    param($Value)
    return ($Value -is [int] -or $Value -is [long]) -and $Value -ge 0
}

function Get-GitLabCoverageHeader {
    # One response header by name, in whichever shape the edition hands headers over: a dictionary
    # of strings or of string lists on an answer, a header collection on a failed one.
    param($Headers, [string]$Name)
    if ($null -eq $Headers) { return $null }
    if ($Headers -is [System.Collections.Specialized.NameValueCollection]) { return $Headers[$Name] }
    if ($Headers -is [System.Collections.IDictionary]) {
        foreach ($key in @($Headers.Keys)) {
            if ([string]::Equals([string]$key, $Name, [StringComparison]::OrdinalIgnoreCase)) { return (@($Headers[$key]) -join ', ') }
        }
        return $null
    }
    foreach ($pair in $Headers) {
        if ([string]::Equals([string]$pair.Key, $Name, [StringComparison]::OrdinalIgnoreCase)) { return (@($pair.Value) -join ', ') }
    }
    return $null
}

function Get-GitLabCoverageNextLink {
    # The address the Link header gives for rel="next", or $null when it names none.
    param($Headers)
    $link = Get-GitLabCoverageHeader $Headers 'Link'
    if ("$link" -match '<([^>]+)>\s*;\s*rel="?next"?') { return $Matches[1] }
    return $null
}

function Get-GitLabCoverageQueryValue {
    param([string]$Uri, [string]$Name)
    $at = $Uri.IndexOf('?')
    if ($at -lt 0) { return $null }
    foreach ($pair in ($Uri.Substring($at + 1) -split '&')) {
        $equals = $pair.IndexOf('=')
        if ($equals -gt 0 -and $pair.Substring(0, $equals) -ceq $Name) { return [Uri]::UnescapeDataString($pair.Substring($equals + 1)) }
    }
    return $null
}

function Get-GitLabCoverageRetryAfter {
    # The seconds GitLab asked to be left alone for, or $null when it named none.
    param($Headers)
    $value = Get-GitLabCoverageHeader $Headers 'Retry-After'
    if ("$value" -match '^\s*(\d+)\s*$') { return [int]$Matches[1] }
    return $null
}

function ConvertFrom-GitLabCoverageList {
    # The elements of an answer that has to be a JSON array, or $null when it isn't one.
    param([string]$Text)
    if (-not "$Text".TrimStart().StartsWith('[')) { return $null }
    try { $parsed = $Text | ConvertFrom-Json } catch { return $null }
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($item in $parsed) { if ($null -ne $item) { $items.Add($item) } }
    return , $items
}

# --- requests ----------------------------------------------------------------------------------

function Invoke-GitLabCoverageRequest {
    <#
        One anonymous GET to gitlab.com, answering Status, Content and Headers. A 4xx or 5xx answer
        comes back as its status with its headers, since a rate limit or a repository that can't
        be read is a record and not a crash. A request that got no answer comes back as status 0.
        Nothing is waited out or tried again here: the ledger is what makes stopping cheap.
    #>
    param([Parameter(Mandatory = $true)][string]$Uri)

    $target = [Uri]$Uri
    if ($target.Scheme -ne 'https' -or $target.Host -ne 'gitlab.com') { throw "The coverage ledger reads gitlab.com only, not $Uri" }
    try {
        $response = Invoke-WebRequest -Uri $Uri -UserAgent 'Hushfacebook-source-audit' -UseBasicParsing -TimeoutSec 120
        $content = $null
        $raw = $response.PSObject.Properties['RawContentStream']
        if ($null -ne $raw -and $null -ne $raw.Value) {
            $content = [Text.Encoding]::UTF8.GetString($raw.Value.ToArray())
        } else {
            $content = $response.Content
            if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
        }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Content = [string]$content; Headers = $response.Headers; Said = $null }
    } catch {
        $status = 0
        $headers = $null
        $answer = $_.Exception.PSObject.Properties['Response']
        if ($null -ne $answer -and $null -ne $answer.Value -and $null -ne $answer.Value.StatusCode) {
            $status = [int]$answer.Value.StatusCode
            $held = $answer.Value.PSObject.Properties['Headers']
            if ($null -ne $held) { $headers = $held.Value }
        }
        return [pscustomobject]@{ Status = $status; Content = $null; Headers = $headers; Said = $_.Exception.Message }
    }
}

function New-GitLabCoverageSession {
    # What one run or one check keeps between requests: how many it made and why it has to stop.
    # WaitPath, when given, is where a 429's wait is kept for the next run (Get-GitLabCoverageWaitPath).
    param([int]$MaxRequests = 0, [int]$DelayMilliseconds = 1000, [string]$WaitPath)
    return [pscustomobject]@{ Requests = 0; MaxRequests = $MaxRequests; DelayMilliseconds = $DelayMilliseconds
        Stopped = $null; RetryAfter = $null; WindowUsed = $false; WindowReset = $null; WaitPath = $WaitPath }
}

function Invoke-GitLabCoverageGet {
    <#
        The next request of a session, or $null when it has to stop first: its request budget is
        used, the last answer left nothing in the rate-limit window, or an earlier answer already
        ended it. A 429 and a request with no answer end the session after they're handed back,
        so whoever asked can record what happened to the thing it was reading.
    #>
    param($Session, [string]$Uri)

    if ($Session.Stopped) { return $null }
    if ($Session.MaxRequests -gt 0 -and $Session.Requests -ge $Session.MaxRequests) { $Session.Stopped = 'request-budget'; return $null }
    if ($Session.WindowUsed) { $Session.Stopped = 'rate-limit-window'; return $null }
    if ($Session.Requests -gt 0 -and $Session.DelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $Session.DelayMilliseconds }
    $Session.Requests++
    $answer = Invoke-GitLabCoverageRequest -Uri $Uri
    $remaining = Get-GitLabCoverageHeader $answer.Headers 'RateLimit-Remaining'
    if ("$remaining" -match '^\d+$' -and [long]$remaining -eq 0) {
        $Session.WindowUsed = $true
        $Session.WindowReset = Get-GitLabCoverageHeader $answer.Headers 'RateLimit-Reset'
    }
    if ($answer.Status -eq 429) {
        $Session.Stopped = 'rate-limited'
        $Session.RetryAfter = Get-GitLabCoverageRetryAfter $answer.Headers
        if ($Session.WaitPath -and $null -ne $Session.RetryAfter) { Save-GitLabCoverageWait -WaitPath $Session.WaitPath -RetryAfter $Session.RetryAfter }
    } elseif ($answer.Status -eq 0) {
        $Session.Stopped = 'no-answer'
    }
    return $answer
}

function Get-GitLabCoverageFailure {
    <#
        What an answer other than 200 is recorded as: its result, its status, and how long GitLab
        asked to be left alone. $null for a 200. A 403 or 404 on a repository is inaccessible: the
        repository is switched off or kept to members, or the project went away after it was
        listed. From outside those can't be told apart, so none of them settles the record.
    #>
    param($Answer, [switch]$Repository)

    if ($Answer.Status -eq 200) { return $null }
    $failure = [ordered]@{ result = 'http-failure'; status = $Answer.Status }
    if ($Answer.Status -eq 429) {
        $failure.result = 'rate-limited'
        $failure.retryAfter = Get-GitLabCoverageRetryAfter $Answer.Headers
    } elseif ($Repository -and $Answer.Status -in @(403, 404)) {
        $failure.result = 'inaccessible'
    } elseif ($Answer.Status -eq 0) {
        $failure.detail = "no answer: $($Answer.Said)"
    }
    return $failure
}

function Get-GitLabCoverageWait {
    <#
        The seconds still left of the wait GitLab asked for, when the ledger's last record is a
        429 that named one. The wait is counted from when that record was written, so it holds
        for the next run too, however soon it is started.
    #>
    param($Record, [datetime]$Now)

    if ($null -eq $Record -or [string]$Record.result -ne 'rate-limited' -or -not (Test-GitLabCoverageId $Record.retryAfter)) { return 0 }
    # One edition reads the time back as a date, the other as the text it was written as.
    $at = $Record.at
    if ($at -isnot [datetime]) {
        $parsed = [datetime]::MinValue
        $styles = [Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal
        if (-not [datetime]::TryParse([string]$at, [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$parsed)) { return 0 }
        $at = $parsed
    }
    $left = ($at.ToUniversalTime().AddSeconds([long]$Record.retryAfter) - $Now.ToUniversalTime()).TotalSeconds
    if ($left -le 0) { return 0 }
    return [int][Math]::Ceiling($left)
}

function Get-GitLabCoverageWaitPath {
    <#
        Where the wait after a 429 is kept for every ledger in one directory. GitLab limits the
        address that asks, not one ledger, and some 429s land where no ledger record is written:
        the ceiling read before a ledger exists, and the sentinel check, which only reads.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)
    $full = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    return Join-Path (Split-Path -Parent $full) 'rate-limit-waits.jsonl'
}

function Save-GitLabCoverageWait {
    # Keeps the wait a 429 asked for, counted from now, as one more line of the waits file, through
    # the ledger's own append. Only the last line counts, since no request goes out until the wait
    # it holds is over.
    param([Parameter(Mandatory = $true)][string]$WaitPath, [long]$RetryAfter)
    $directory = Split-Path -Parent $WaitPath
    if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    [void](Add-GitLabCoverageRecord -Path $WaitPath -Record ([ordered]@{ kind = 'wait'; result = 'rate-limited'; retryAfter = $RetryAfter }))
}

function Get-GitLabCoverageKeptWait {
    # The seconds left of the wait kept beside the ledger at Path, or 0. The waits file is read the
    # way the ledger is, so a line cut short by a crash is passed over.
    param([Parameter(Mandatory = $true)][string]$Path, [datetime]$Now)
    $waits = Read-GitLabCoverageLedger -Path (Get-GitLabCoverageWaitPath -Path $Path)
    if ($waits.Records.Count -eq 0) { return 0 }
    return Get-GitLabCoverageWait -Record $waits.Records[$waits.Records.Count - 1] -Now $Now
}

# --- the ledger on disk --------------------------------------------------------------------------

function Read-GitLabCoverageLedger {
    <#
        Every record in the ledger, in order. A record is a whole line: bytes after the last
        newline are a write that never finished (Partial), and Length is where the last whole
        record ends. A whole line that isn't a record is a problem, not something to step over.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    $records = New-Object System.Collections.Generic.List[object]
    $problems = New-Object System.Collections.Generic.List[string]
    $ledger = [pscustomobject]@{ Exists = $false; Records = $records; Problems = $problems; Partial = $false; Length = [long]0 }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $ledger }
    $ledger.Exists = $true
    $bytes = [IO.File]::ReadAllBytes($Path)
    $end = [Array]::LastIndexOf($bytes, [byte]10) + 1
    $ledger.Partial = $end -lt $bytes.Length
    $ledger.Length = [long]$end
    $number = 0
    foreach ($line in ([Text.Encoding]::UTF8.GetString($bytes, 0, $end) -split "`n")) {
        $number++
        if (-not $line.Trim()) { continue }
        $record = $null
        try { $record = $line | ConvertFrom-Json } catch { $record = $null }
        if ($null -eq $record -or $record -is [array] -or -not [string]$record.kind) {
            $problems.Add("line $number of the ledger is not a record")
            continue
        }
        $records.Add($record)
    }
    return $ledger
}

function Add-GitLabCoverageRecord {
    <#
        Appends one record as one line and hands it back as the ledger now holds it, so a run's
        picture of the ledger is always what reading the file again would give.
    #>
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)]$Record)

    $Record.at = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    $line = $Record | ConvertTo-Json -Depth 6 -Compress
    [IO.File]::AppendAllText($Path, $line + "`n", (New-Object Text.UTF8Encoding $false))
    return ($line | ConvertFrom-Json)
}

function New-GitLabCoverageState {
    return [pscustomobject]@{
        Exists = $false; Partial = $false; Count = 0; Run = $null
        # Where the page list has got to, the unresolved page waiting there if there is one, and
        # whether a page has reached the ceiling.
        Cursor = [long]0; Pending = $null; Done = $false; Pages = 0
        Projects = [ordered]@{}; Trees = [ordered]@{}
        Problems = (New-Object System.Collections.Generic.List[string])
    }
}

function Add-GitLabCoverageStateRecord {
    <#
        Takes one ledger record into the state, in the order the ledger holds them, and names
        whatever about it the ledger's rules refuse. A refused record changes nothing else, so one
        bad line can't make the rest look settled.
    #>
    param($State, $Record)

    $first = $State.Count -eq 0
    $State.Count++
    $settled = Get-GitLabCoverageSettled
    $kind = [string]$Record.kind
    $result = [string]$Record.result

    if ($kind -eq 'run') {
        if (-not $first) {
            $State.Problems.Add('a run record is not the first record: a ledger pins its range once')
        } elseif ($Record.schemaVersion -ne 1 -or [string]$Record.host -ne 'gitlab.com' -or
            -not (Test-GitLabCoverageId $Record.startAfter) -or -not (Test-GitLabCoverageId $Record.ceiling) -or
            [long]$Record.ceiling -le [long]$Record.startAfter) {
            $State.Problems.Add('the run record names no range on gitlab.com this ledger can be held to')
        } else {
            $State.Run = $Record
            $State.Cursor = [long]$Record.startAfter
        }
        return
    }
    if ($null -eq $State.Run) {
        $State.Problems.Add("a $kind record has no run record before it")
        return
    }

    if ($kind -eq 'page') {
        if ($State.Done) {
            $State.Problems.Add("a page asked after $($Record.after) follows the page that reached the ceiling")
            return
        }
        if (-not (Test-GitLabCoverageId $Record.after) -or [long]$Record.after -ne $State.Cursor) {
            $State.Problems.Add("a page asked after $($Record.after) is out of sequence: the list had reached $($State.Cursor), so a page is missing or repeated")
            return
        }
        if ($settled.page -notcontains $result) {
            $State.Pending = $Record
            return
        }
        $ceiling = [long]$State.Run.ceiling
        $previous = $State.Cursor
        $listed = @($Record.projects | Where-Object { $null -ne $_ })
        foreach ($project in $listed) {
            if (-not (Test-GitLabCoverageId $project.id) -or [long]$project.id -le $previous -or [long]$project.id -gt $ceiling) {
                $State.Problems.Add("the page asked after $($State.Cursor) lists project '$($project.id)', which is not above the id before it or is past the ceiling")
                return
            }
            $previous = [long]$project.id
        }
        $reached = [string]$Record.end -eq 'ceiling'
        if (-not (Test-GitLabCoverageId $Record.last) -or [long]$Record.last -lt $previous) {
            $State.Problems.Add("the page asked after $($State.Cursor) names a last id below the projects it lists")
            return
        }
        if ($reached -and [long]$Record.last -lt $ceiling) {
            $State.Problems.Add("the page asked after $($State.Cursor) says it reached the ceiling, and its last id is $($Record.last)")
            return
        }
        if (-not $reached -and ($listed.Count -eq 0 -or [long]$Record.last -ne $previous -or
            -not (Test-GitLabCoverageId $Record.next) -or [long]$Record.next -ne [long]$Record.last)) {
            $State.Problems.Add("the page asked after $($State.Cursor) does not hand the list on from its own last id")
            return
        }
        foreach ($project in $listed) {
            $State.Projects[[string]$project.id] = [pscustomobject]@{ Id = [long]$project.id; Path = [string]$project.path
                Record = $null; Settled = $false; Trees = (New-Object System.Collections.Generic.List[string]) }
        }
        $State.Pending = $null
        $State.Pages++
        if ($reached) { $State.Done = $true } else { $State.Cursor = [long]$Record.next }
        return
    }

    if ($kind -eq 'project') {
        $key = [string]$Record.id
        if (-not $State.Projects.Contains($key)) {
            $State.Problems.Add("a project record names '$key', which no page before it lists")
            return
        }
        $project = $State.Projects[$key]
        if ($project.Settled) {
            $State.Problems.Add("project $key has a record after the one that settled it, so it was read twice")
            return
        }
        $project.Record = $Record
        if ($settled.project -notcontains $result) { return }
        $branches = @($Record.branches | Where-Object { $null -ne $_ })
        if (($result -eq 'empty') -ne ($branches.Count -eq 0)) {
            $State.Problems.Add("project $key is recorded as $result with $($branches.Count) branch(es)")
            return
        }
        $names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($branch in $branches) {
            if (-not [string]$branch.name -or -not $names.Add([string]$branch.name) -or
                [string]$branch.commit -notmatch '^([0-9a-f]{40}|[0-9a-f]{64})$') {
                $State.Problems.Add("project $key pins branch '$($branch.name)' twice or at no commit")
                return
            }
        }
        # Branches at one commit share its tree, which is read once.
        foreach ($branch in $branches) {
            $treeKey = "$key@$([string]$branch.commit)"
            if (-not $State.Trees.Contains($treeKey)) {
                $State.Trees[$treeKey] = [pscustomobject]@{ Project = $key; Commit = [string]$branch.commit; Record = $null; Settled = $false }
                $project.Trees.Add($treeKey)
            }
        }
        $project.Settled = $true
        return
    }

    if ($kind -eq 'tree') {
        $key = "$([string]$Record.project)@$([string]$Record.commit)"
        if (-not $State.Trees.Contains($key)) {
            $State.Problems.Add("a tree record names $key, which no settled project pins")
            return
        }
        $tree = $State.Trees[$key]
        if ($tree.Settled) {
            $State.Problems.Add("tree $key has a record after the one that settled it, so it was read twice")
            return
        }
        $tree.Record = $Record
        if ($settled.tree -notcontains $result) { return }
        $files = @($Record.files | Where-Object { $null -ne $_ })
        $paths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($file in $files) {
            if (-not [string]$file.path -or -not $paths.Add([string]$file.path)) {
                $State.Problems.Add("tree $key lists the path '$($file.path)' twice or with no name")
                return
            }
        }
        if (-not (Test-GitLabCoverageId $Record.entries) -or [long]$Record.entries -ne $files.Count) {
            $State.Problems.Add("tree $key says it holds $($Record.entries) entries and lists $($files.Count)")
            return
        }
        $tree.Settled = $true
        return
    }

    $State.Problems.Add("a record of kind '$kind' is not one the ledger knows")
}

function Get-GitLabCoverageState {
    # The ledger as it stands, from the file alone.
    param([Parameter(Mandatory = $true)][string]$Path)

    $state = New-GitLabCoverageState
    $ledger = Read-GitLabCoverageLedger -Path $Path
    $state.Exists = $ledger.Exists
    $state.Partial = $ledger.Partial
    foreach ($problem in $ledger.Problems) { $state.Problems.Add($problem) }
    foreach ($record in $ledger.Records) { Add-GitLabCoverageStateRecord $state $record }
    return $state
}

function Test-GitLabCoverage {
    <#
    .SYNOPSIS
        Whether the ledger covers every public project up to its ceiling, from the ledger alone.
    .DESCRIPTION
        Complete means the page list runs unbroken from the start of the range to a page that
        reached the ceiling, every project those pages list has its branches pinned or is settled
        as empty, and every commit a branch is pinned at has its tree pinned. Unresolved lists
        every page, project and tree that isn't there yet with the last result recorded for it
        (not-read when nothing is), and Problems lists whatever about the ledger can't be trusted:
        a page out of sequence, a record for something already settled, a line that isn't a
        record, a last record that was never finished.

        FullRun says whether the range is all of GitLab: from the first id up to a ceiling that
        was read from GitLab. A ledger for part of that range can be complete and still says
        nothing about the census.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    $state = Get-GitLabCoverageState -Path $Path
    $problems = New-Object System.Collections.Generic.List[string]
    foreach ($problem in $state.Problems) { $problems.Add($problem) }
    if (-not $state.Exists) {
        $problems.Add('there is no ledger yet')
    } elseif ($null -eq $state.Run -and $problems.Count -eq 0) {
        $problems.Add('the ledger has no run record')
    }
    if ($state.Partial) {
        $problems.Add('the ledger ends in a record that was never finished; the next run drops it and reads that part again')
    }

    $unresolved = New-Object System.Collections.Generic.List[object]
    function Add-Unresolved([string]$Kind, [string]$Key, $Record) {
        $result = 'not-read'
        $status = $null
        $detail = $null
        if ($null -ne $Record) {
            $result = [string]$Record.result
            $status = $Record.status
            $detail = $Record.detail
        }
        $unresolved.Add([pscustomobject]@{ Kind = $Kind; Key = $Key; Result = $result; Status = $status; Detail = $detail })
    }
    if ($null -ne $state.Run -and -not $state.Done) { Add-Unresolved 'page' "after $($state.Cursor)" $state.Pending }
    $branches = 0
    $trees = 0
    foreach ($project in $state.Projects.Values) {
        if (-not $project.Settled) { Add-Unresolved 'project' ([string]$project.Id) $project.Record; continue }
        $branches += @($project.Record.branches | Where-Object { $null -ne $_ }).Count
    }
    foreach ($key in $state.Trees.Keys) {
        if (-not $state.Trees[$key].Settled) { Add-Unresolved 'tree' $key $state.Trees[$key].Record } else { $trees++ }
    }

    $startAfter = $null
    $ceiling = $null
    $fullRun = $false
    if ($null -ne $state.Run) {
        $startAfter = [long]$state.Run.startAfter
        $ceiling = [long]$state.Run.ceiling
        $fullRun = $startAfter -eq 0 -and [string]$state.Run.ceilingSource -eq 'highest-public-id'
    }
    return [pscustomobject]@{
        Complete = ($problems.Count -eq 0 -and $unresolved.Count -eq 0 -and $state.Done)
        FullRun = $fullRun; StartAfter = $startAfter; Ceiling = $ceiling; Cursor = $state.Cursor
        Pages = $state.Pages; Projects = $state.Projects.Count; Branches = $branches; Trees = $trees
        Unresolved = $unresolved.ToArray(); Problems = [string[]]$problems.ToArray()
    }
}

# --- reading GitLab into records ------------------------------------------------------------------

function Read-GitLabCoveragePage {
    <#
        One page of the public project list, asked after the cursor, as the record it becomes, or
        $null when the session stopped before asking. The page is refused when a project on it is
        not above the id before it, which is what a repeated page or a page from before the cursor
        looks like. It is read only when it reaches the ceiling or hands on to a next page from
        its own last id. Projects past the ceiling are counted and left out.
    #>
    param($Session, [long]$After, [long]$Ceiling, [int]$PerPage)

    $record = [ordered]@{ kind = 'page'; after = $After }
    $answer = Invoke-GitLabCoverageGet $Session ((Get-GitLabCoverageApi) +
        "/projects?pagination=keyset&order_by=id&sort=asc&per_page=$PerPage&id_after=$After")
    if ($null -eq $answer) { return $null }
    $failure = Get-GitLabCoverageFailure $answer
    if ($null -ne $failure) {
        foreach ($key in $failure.Keys) { $record[$key] = $failure[$key] }
        return $record
    }
    $items = ConvertFrom-GitLabCoverageList $answer.Content
    if ($null -eq $items) {
        $record.result = 'malformed'
        $record.detail = 'the answer is not a list of projects'
        return $record
    }
    $projects = New-Object System.Collections.Generic.List[object]
    $previous = $After
    $beyond = 0
    foreach ($item in $items) {
        if (-not (Test-GitLabCoverageId $item.id)) {
            $record.result = 'malformed'
            $record.detail = 'a listed project has no id'
            return $record
        }
        if ([long]$item.id -le $previous) {
            $record.result = 'repeated-page'
            $record.detail = "project $($item.id) is not above $previous, the id before it, so the page repeats or runs backwards"
            return $record
        }
        $previous = [long]$item.id
        if ($previous -gt $Ceiling) { $beyond++ } else { $projects.Add([ordered]@{ id = $previous; path = [string]$item.path_with_namespace }) }
    }
    if ($items.Count -eq 0) {
        $record.result = 'ended-early'
        $record.detail = "the list ran out after $After, below the ceiling $Ceiling"
        return $record
    }
    if ($previous -lt $Ceiling) {
        $next = Get-GitLabCoverageNextLink $answer.Headers
        if (-not $next) {
            $record.result = 'ended-early'
            $record.detail = "the list named no page after $previous, below the ceiling $Ceiling"
            return $record
        }
        $linked = Get-GitLabCoverageQueryValue $next 'id_after'
        if ("$linked" -ne "$previous") {
            $record.result = 'link-mismatch'
            $record.detail = "the next link carries on after '$linked', and this page ended at $previous"
            return $record
        }
    }
    $record.result = 'read'
    $record.served = $items.Count
    $record.last = $previous
    if ($previous -lt $Ceiling) {
        $record.next = $previous
        $record.end = $null
    } else {
        $record.next = $null
        $record.end = 'ceiling'
    }
    $record.beyond = $beyond
    $record.projects = $projects.ToArray()
    return $record
}

function Read-GitLabCoverageBranches {
    <#
        Every branch a project declares, each at the commit it points to now, as the project's
        record, or $null when the session stopped before the list was whole. A list that can't be
        read to its end, names a branch twice or disagrees with GitLab's own total is incomplete.
        A whole list with no branch in it is an empty project.
    #>
    param($Session, $Project, [int]$MaxBranchPages)

    $record = [ordered]@{ kind = 'project'; id = [long]$Project.Id; path = [string]$Project.Path }
    $branches = New-Object System.Collections.Generic.List[object]
    $names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $total = $null
    for ($page = 1; ; $page++) {
        $answer = Invoke-GitLabCoverageGet $Session ((Get-GitLabCoverageApi) + "/projects/$($Project.Id)/repository/branches?per_page=100&page=$page")
        if ($null -eq $answer) { return $null }
        $failure = Get-GitLabCoverageFailure $answer -Repository
        if ($null -ne $failure) {
            foreach ($key in $failure.Keys) { $record[$key] = $failure[$key] }
            return $record
        }
        $items = ConvertFrom-GitLabCoverageList $answer.Content
        if ($null -eq $items) {
            $record.result = 'malformed'
            $record.detail = 'the answer is not a list of branches'
            return $record
        }
        foreach ($item in $items) {
            $name = [string]$item.name
            $commit = [string]$item.commit.id
            if (-not $name -or $commit -notmatch '^([0-9a-f]{40}|[0-9a-f]{64})$') {
                $record.result = 'malformed'
                $record.detail = "branch '$name' came with no commit id"
                return $record
            }
            if (-not $names.Add($name)) {
                $record.result = 'incomplete'
                $record.detail = "branch '$name' was listed twice, so the pages moved while they were read"
                return $record
            }
            $branches.Add([ordered]@{ name = $name; commit = $commit })
        }
        $said = Get-GitLabCoverageHeader $answer.Headers 'X-Total'
        if ("$said" -match '^\d+$') { $total = [long]$said }
        $nextPage = Get-GitLabCoverageHeader $answer.Headers 'X-Next-Page'
        # Without the header, a full page is the only sign there may be another.
        $more = if ($null -ne $nextPage) { [bool]"$nextPage".Trim() } else { $items.Count -eq 100 }
        if (-not $more) { break }
        if ($items.Count -eq 0 -or $page -ge $MaxBranchPages) {
            $record.result = 'incomplete'
            $record.detail = "the branch list did not end within $page page(s)"
            return $record
        }
    }
    if ($null -ne $total -and $total -ne $branches.Count) {
        $record.result = 'incomplete'
        $record.detail = "GitLab counts $total branch(es) and listed $($branches.Count)"
        return $record
    }
    $record.result = if ($branches.Count -eq 0) { 'empty' } else { 'pinned' }
    $record.branches = $branches.ToArray()
    return $record
}

function Read-GitLabCoverageTree {
    <#
        Every entry in the tree of one commit, as its record, or $null when the session stopped
        before the tree was whole. The tree is always asked for by the commit id. Only the page
        token is taken from GitLab's next link; the address is built here, so a link can't move
        the read to a branch name or to another project. A path served twice, an empty page that
        promises more and a next link with no token are a truncated tree. A tree still going after
        -MaxTreePages is too large: nothing is recorded as read that wasn't read to its end.
    #>
    param($Session, [string]$ProjectId, [string]$Commit, [int]$MaxTreePages)

    $record = [ordered]@{ kind = 'tree'; project = [long]$ProjectId; commit = $Commit }
    $files = New-Object System.Collections.Generic.List[object]
    $paths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $token = $null
    for ($page = 1; ; $page++) {
        $uri = (Get-GitLabCoverageApi) + "/projects/$ProjectId/repository/tree?ref=$Commit&recursive=true&pagination=keyset&per_page=100"
        if ($token) { $uri += '&page_token=' + [Uri]::EscapeDataString($token) }
        $answer = Invoke-GitLabCoverageGet $Session $uri
        if ($null -eq $answer) { return $null }
        $failure = Get-GitLabCoverageFailure $answer -Repository
        if ($null -ne $failure) {
            foreach ($key in $failure.Keys) { $record[$key] = $failure[$key] }
            return $record
        }
        $items = ConvertFrom-GitLabCoverageList $answer.Content
        if ($null -eq $items) {
            $record.result = 'malformed'
            $record.detail = 'the answer is not a list of tree entries'
            return $record
        }
        foreach ($item in $items) {
            $path = [string]$item.path
            if (-not $path -or -not [string]$item.type -or -not [string]$item.id) {
                $record.result = 'malformed'
                $record.detail = "a tree entry came without its path, type or id ('$path')"
                return $record
            }
            if (-not $paths.Add($path)) {
                $record.result = 'truncated'
                $record.detail = "the path '$path' was served twice, so a page repeated"
                return $record
            }
            $files.Add([ordered]@{ path = $path; type = [string]$item.type; id = [string]$item.id; mode = [string]$item.mode })
        }
        $next = Get-GitLabCoverageNextLink $answer.Headers
        if (-not $next) { break }
        $token = Get-GitLabCoverageQueryValue $next 'page_token'
        if (-not $token -or $items.Count -eq 0) {
            $record.result = 'truncated'
            $record.detail = "page $page promised more and gave no way to read it"
            return $record
        }
        if ($page -ge $MaxTreePages) {
            $record.result = 'too-large'
            $record.detail = "the tree was still going after $page page(s) and $($files.Count) entries"
            return $record
        }
    }
    $record.result = 'pinned'
    $record.pages = $page
    $record.entries = $files.Count
    $record.files = $files.ToArray()
    return $record
}

function Invoke-GitLabCoverageRun {
    <#
    .SYNOPSIS
        Starts the ledger or carries it on from where it stopped, and says why this run stopped.
    .DESCRIPTION
        A new ledger pins its range first: -StartAfter (0 for the beginning) up to -Ceiling, or up
        to the highest public project id when no ceiling is given. That is read once and never
        again. A ledger that exists keeps the range it was started with, and a run that asks for
        another is refused.

        The run then works through what the ledger doesn't have settled, oldest first: the
        branches of each listed project, the tree of each pinned commit, and the next page once
        everything listed so far has been tried. Whatever was unresolved is tried once more.
        Nothing settled is asked for again. Every answer is appended before the next request
        goes out, so the run can be stopped anywhere; a record cut off mid-write is dropped at the
        start of the next run and that part is read again.

        Stopped is $null when the run did everything it could, else request-budget, rate-limited
        (a 429, with RetryAfter in seconds when GitLab named it), rate-limit-window (the last
        answer left nothing in the window; WindowReset is when it reopens), no-answer or
        ceiling-unread. WindowUsed says the window was empty when the run ended, whatever ended
        it. A run started inside the wait a 429 asked for makes no request: it stops
        as rate-limited with what is left of the wait. That holds for a 429 on any ledger or check
        in the same directory, the ceiling read and the sentinel check included, since the wait is
        kept beside the ledgers (Get-GitLabCoverageWaitPath). Test-GitLabCoverage says what is
        still open.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [long]$StartAfter = 0,
        # 0 reads the highest public project id from GitLab and pins that.
        [long]$Ceiling = 0,
        [int]$PerPage = 100,
        # 0 for no limit. A tree that needs more requests than this can't be finished.
        [int]$MaxRequests = 0,
        [int]$DelayMilliseconds = 1000,
        [int]$MaxBranchPages = 20,
        # Fifty pages is 5,000 entries, which also keeps one record within what Windows
        # PowerShell's JSON reader takes.
        [int]$MaxTreePages = 50,
        # When the run starts, which is what the wait after a 429 is measured against.
        [datetime]$Now = [datetime]::UtcNow
    )

    if ($PerPage -lt 1 -or $PerPage -gt 100) { throw '-PerPage is 1 to 100, which is what GitLab serves.' }
    $directory = Split-Path -Parent $Path
    if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $ledger = Read-GitLabCoverageLedger -Path $Path
    if ($ledger.Partial) {
        # A record that was being written when the last run died. What it stood for is read again.
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Write)
        try { $stream.SetLength($ledger.Length) } finally { $stream.Dispose() }
    }
    $state = Get-GitLabCoverageState -Path $Path
    if ($state.Problems.Count -gt 0) {
        throw "The coverage ledger can't be added to until this is settled: $($state.Problems[0])"
    }
    $session = New-GitLabCoverageSession -MaxRequests $MaxRequests -DelayMilliseconds $DelayMilliseconds -WaitPath (Get-GitLabCoverageWaitPath -Path $Path)
    function Add-Record($Record) {
        $held = Add-GitLabCoverageRecord -Path $Path -Record $Record
        Add-GitLabCoverageStateRecord $state $held
        if ($state.Problems.Count -gt 0) { throw "The run wrote a record its own ledger refuses: $($state.Problems[0])" }
    }
    function Get-Outcome {
        return [pscustomobject]@{ Requests = $session.Requests; Stopped = $session.Stopped
            RetryAfter = $session.RetryAfter; WindowUsed = $session.WindowUsed; WindowReset = $session.WindowReset }
    }

    if ($null -ne $state.Run -and (($Ceiling -gt 0 -and $Ceiling -ne [long]$state.Run.ceiling) -or
        ($PSBoundParameters.ContainsKey('StartAfter') -and $StartAfter -ne [long]$state.Run.startAfter))) {
        throw ("This ledger is pinned to the projects after $($state.Run.startAfter) up to $($state.Run.ceiling). " +
            'A run can''t move that; start another ledger for another range.')
    }
    # A 429 ended an earlier run or check. Until the wait it was given is over, this one asks for
    # nothing. The ledger's last record still counts for a ledger written before the kept wait.
    $wait = Get-GitLabCoverageKeptWait -Path $Path -Now $Now
    if ($ledger.Records.Count -gt 0) {
        $wait = [Math]::Max($wait, (Get-GitLabCoverageWait -Record $ledger.Records[$ledger.Records.Count - 1] -Now $Now))
    }
    if ($wait -gt 0) {
        $session.Stopped = 'rate-limited'
        $session.RetryAfter = $wait
        return (Get-Outcome)
    }

    if ($null -eq $state.Run) {
        $source = 'given'
        if ($Ceiling -le 0) {
            $source = 'highest-public-id'
            $answer = Invoke-GitLabCoverageGet $session ((Get-GitLabCoverageApi) + '/projects?pagination=keyset&order_by=id&sort=desc&per_page=1')
            $newest = $null
            if ($null -ne $answer -and $answer.Status -eq 200) { $newest = ConvertFrom-GitLabCoverageList $answer.Content }
            if ($null -eq $newest -or $newest.Count -eq 0 -or -not (Test-GitLabCoverageId $newest[0].id)) {
                # Nothing is pinned yet, so there's no ledger to record this against.
                if (-not $session.Stopped) { $session.Stopped = 'ceiling-unread' }
                return (Get-Outcome)
            }
            $Ceiling = [long]$newest[0].id
        }
        if ($StartAfter -lt 0 -or $Ceiling -le $StartAfter) { throw "The range after $StartAfter up to $Ceiling holds no project id." }
        Add-Record ([ordered]@{ kind = 'run'; schemaVersion = 1; host = 'gitlab.com'; startAfter = $StartAfter
            ceiling = $Ceiling; ceilingSource = $source })
    }

    # What earlier runs listed and didn't settle comes first, in the order it was listed.
    $queue = New-Object System.Collections.Generic.Queue[object]
    foreach ($project in $state.Projects.Values) {
        if (-not $project.Settled) {
            $queue.Enqueue([pscustomobject]@{ Kind = 'project'; Key = [string]$project.Id })
            continue
        }
        foreach ($treeKey in $project.Trees) {
            if (-not $state.Trees[$treeKey].Settled) { $queue.Enqueue([pscustomobject]@{ Kind = 'tree'; Key = $treeKey }) }
        }
    }
    $pageTried = $false
    while (-not $session.Stopped) {
        if ($queue.Count -gt 0) {
            $work = $queue.Dequeue()
            if ($work.Kind -eq 'project') {
                $project = $state.Projects[$work.Key]
                $record = Read-GitLabCoverageBranches -Session $session -Project $project -MaxBranchPages $MaxBranchPages
                if ($null -eq $record) { break }
                Add-Record $record
                if ($project.Settled) {
                    foreach ($treeKey in $project.Trees) { $queue.Enqueue([pscustomobject]@{ Kind = 'tree'; Key = $treeKey }) }
                }
            } else {
                $tree = $state.Trees[$work.Key]
                $record = Read-GitLabCoverageTree -Session $session -ProjectId $tree.Project -Commit $tree.Commit -MaxTreePages $MaxTreePages
                if ($null -eq $record) { break }
                Add-Record $record
            }
            continue
        }
        # Every project listed so far has been tried, so the list can move on. A page that stays
        # unresolved is asked for once a run.
        if ($state.Done -or $pageTried) { break }
        $record = Read-GitLabCoveragePage -Session $session -After $state.Cursor -Ceiling ([long]$state.Run.ceiling) -PerPage $PerPage
        if ($null -eq $record) { break }
        Add-Record $record
        if ($null -ne $state.Pending) {
            $pageTried = $true
            continue
        }
        foreach ($listed in @($record.projects)) { $queue.Enqueue([pscustomobject]@{ Kind = 'project'; Key = [string]$listed.id }) }
    }
    return (Get-Outcome)
}

# --- the second way --------------------------------------------------------------------------------

function Compare-GitLabCoverageSentinel {
    <#
    .SYNOPSIS
        Checks one project's ledger records a second way, against GitLab asked directly.
    .DESCRIPTION
        The ledger got its records from the project list, the paged branch list and the keyset
        tree. This reads the same facts by other routes: the project by its id, each branch by its
        name, the branch count from GitLab's own total, the commit list of a project the ledger
        calls empty, and each tree again in numbered pages with their total. Every field is
        compared, and every difference comes back in words.

        -Shape is what the project was picked for (Get-GitLabCoverageSentinels). A project that no
        longer has that shape is a difference too, since the check would prove less than it says.
        Nothing here writes to the ledger. A 429 keeps its wait beside it like a run's does, and a
        check started inside a kept wait asks for nothing.
    #>
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][long]$ProjectId,
        [string]$Shape, [int]$DelayMilliseconds = 1000,
        # When the check starts, which is what the wait after a 429 is measured against.
        [datetime]$Now = [datetime]::UtcNow)

    $api = Get-GitLabCoverageApi
    $state = Get-GitLabCoverageState -Path $Path
    $session = New-GitLabCoverageSession -DelayMilliseconds $DelayMilliseconds -WaitPath (Get-GitLabCoverageWaitPath -Path $Path)
    $differences = New-Object System.Collections.Generic.List[string]
    $compared = 0
    function Get-Direct([string]$Uri, [string]$What) {
        $answer = Invoke-GitLabCoverageGet $session $Uri
        if ($null -eq $answer) {
            $differences.Add("$What was not asked for: the check had stopped ($($session.Stopped))")
            return $null
        }
        if ($answer.Status -ne 200) {
            $differences.Add("$What answered HTTP $($answer.Status) when asked directly")
            return $null
        }
        return $answer
    }
    function Get-Outcome {
        return [pscustomobject]@{ ProjectId = $ProjectId; Shape = $Shape; Matches = ($differences.Count -eq 0)
            Compared = $compared; Differences = [string[]]$differences.ToArray(); Requests = $session.Requests
            Stopped = $session.Stopped; RetryAfter = $session.RetryAfter; WindowUsed = $session.WindowUsed }
    }

    $wait = Get-GitLabCoverageKeptWait -Path $Path -Now $Now
    if ($wait -gt 0) {
        $session.Stopped = 'rate-limited'
        $session.RetryAfter = $wait
        $differences.Add("nothing was asked for: $wait s of the wait GitLab asked for are left")
        return (Get-Outcome)
    }

    $key = [string]$ProjectId
    $project = $null
    if ($state.Projects.Contains($key)) { $project = $state.Projects[$key] }
    if ($state.Problems.Count -gt 0 -or $null -eq $project -or $null -eq $project.Record) {
        $differences.Add("the ledger holds no record of project $key that can be checked")
        return (Get-Outcome)
    }
    $record = $project.Record
    $result = [string]$record.result
    $branches = @($record.branches | Where-Object { $null -ne $_ })

    $answer = Get-Direct "$api/projects/$key" "project $key"
    if ($null -ne $answer) {
        $direct = $null
        try { $direct = $answer.Content | ConvertFrom-Json } catch { $direct = $null }
        $compared += 2
        if ($null -eq $direct -or [string]$direct.id -ne $key) {
            $differences.Add("project ${key}: asked directly, it answers as '$($direct.id)'")
        } elseif ([string]$direct.path_with_namespace -cne [string]$project.Path) {
            $differences.Add("project $key path: the ledger has '$($project.Path)', GitLab has '$($direct.path_with_namespace)'")
        }
    }

    if ($result -eq 'empty') {
        $answer = Get-Direct "$api/projects/$key/repository/commits?per_page=1" "the commit list of project $key"
        if ($null -ne $answer) {
            $commits = ConvertFrom-GitLabCoverageList $answer.Content
            $compared++
            if ($null -eq $commits -or $commits.Count -ne 0) {
                $differences.Add("project ${key}: the ledger calls it empty, and its commit list is not")
            }
        }
    } elseif ($result -eq 'pinned') {
        $answer = Get-Direct "$api/projects/$key/repository/branches?per_page=1" "the branch total of project $key"
        if ($null -ne $answer) {
            $total = Get-GitLabCoverageHeader $answer.Headers 'X-Total'
            $compared++
            if ("$total" -ne "$($branches.Count)") {
                $differences.Add("project $key branches: the ledger has $($branches.Count), GitLab counts '$total'")
            }
        }
        foreach ($branch in $branches) {
            $name = [string]$branch.name
            $answer = Get-Direct ("$api/projects/$key/repository/branches/" + [Uri]::EscapeDataString($name)) "branch '$name' of project $key"
            if ($null -eq $answer) { continue }
            $direct = $null
            try { $direct = $answer.Content | ConvertFrom-Json } catch { $direct = $null }
            $compared += 2
            if ($null -eq $direct -or [string]$direct.name -cne $name) {
                $differences.Add("project $key branch '$name': asked directly, it answers as '$($direct.name)'")
            } elseif ([string]$direct.commit.id -ne [string]$branch.commit) {
                $differences.Add("project $key branch '$name': the ledger pins $($branch.commit), GitLab has $($direct.commit.id)")
            }
        }
        foreach ($treeKey in $project.Trees) {
            $tree = $state.Trees[$treeKey]
            if (-not $tree.Settled) {
                $differences.Add("tree ${treeKey}: the ledger has not pinned it")
                continue
            }
            $direct = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
            $total = $null
            $whole = $true
            for ($page = 1; ; $page++) {
                $answer = Get-Direct "$api/projects/$key/repository/tree?ref=$($tree.Commit)&recursive=true&per_page=100&page=$page" "page $page of tree $treeKey"
                $items = $null
                if ($null -ne $answer) { $items = ConvertFrom-GitLabCoverageList $answer.Content }
                if ($null -eq $items) {
                    if ($null -ne $answer) { $differences.Add("page $page of tree ${treeKey}: asked directly, the answer is not a list") }
                    $whole = $false
                    break
                }
                foreach ($item in $items) { $direct[[string]$item.path] = $item }
                $said = Get-GitLabCoverageHeader $answer.Headers 'X-Total'
                if ("$said" -match '^\d+$') { $total = [long]$said }
                if (-not "$(Get-GitLabCoverageHeader $answer.Headers 'X-Next-Page')".Trim()) { break }
            }
            if (-not $whole) { continue }
            $files = @($tree.Record.files | Where-Object { $null -ne $_ })
            $compared++
            if ($null -ne $total -and $total -ne $files.Count) {
                $differences.Add("tree ${treeKey}: the ledger has $($files.Count) entries, GitLab counts $total")
            }
            foreach ($file in $files) {
                $path = [string]$file.path
                $compared++
                if (-not $direct.ContainsKey($path)) {
                    $differences.Add("tree ${treeKey}: '$path' is in the ledger and not in the tree read directly")
                    continue
                }
                foreach ($field in 'type', 'id', 'mode') {
                    $compared++
                    if ([string]$file.$field -cne [string]$direct[$path].$field) {
                        $differences.Add("tree $treeKey '$path' ${field}: the ledger has '$($file.$field)', GitLab has '$($direct[$path].$field)'")
                    }
                }
                [void]$direct.Remove($path)
            }
            foreach ($path in @($direct.Keys)) {
                $differences.Add("tree ${treeKey}: '$path' is in the tree read directly and not in the ledger")
            }
        }
    } else {
        $differences.Add("project ${key}: the ledger has not settled it ($result)")
    }

    $paged = @($project.Trees | Where-Object { $state.Trees[$_].Settled -and [long]$state.Trees[$_].Record.pages -ge 2 }).Count
    if (($Shape -eq 'empty' -and $result -ne 'empty') -or
        ($Shape -eq 'multi-branch' -and ($result -ne 'pinned' -or $branches.Count -lt 2)) -or
        ($Shape -eq 'large-tree' -and $paged -eq 0)) {
        $differences.Add("project $key was picked as a $Shape project and no longer is one, so pick another")
    }
    return (Get-Outcome)
}
