<#
    THE PHASE 0 GATE.

        "Kill -9 the server mid-session, ten times, at random moments.
         Zero data loss, every time. If you can't say that out loud,
         you are not past phase 0."

    What is actually proved
    -----------------------
    For every write the plugin ACKNOWLEDGED as committed, the value is still
    there after the process is destroyed.

    The crash probe writes a strictly increasing counter, each in its own
    transaction, and logs "[PROBE] COMMIT n" only after commit() returns. On the
    next boot it reads the counter back and logs "[PROBE] RECOVERED m".

    Each round asserts  m >= max(n)  where max(n) is taken from the log of the
    run that was killed. That holds because a COMMIT line exists only if the
    commit returned, and SQLite in WAL mode guarantees committed transactions
    survive an application crash.

    The log is buffered, so it can lag reality — which means this test can
    report a false FAILURE but never a false PASS. A test that can only err
    toward the alarm is one you can believe.

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\crashtest.ps1
        ... -Rounds 10 -MinRunSeconds 8 -MaxRunSeconds 25
#>
[CmdletBinding()]
param(
    [int]$Rounds = 10,
    [int]$MinRunSeconds = 8,
    [int]$MaxRunSeconds = 25
)

$ErrorActionPreference = 'Continue'

$root      = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $root 'server'
$log       = Join-Path $serverDir 'logs\latest.log'
$startPs1  = Join-Path $PSScriptRoot 'start-server.ps1'
$rconPs1   = Join-Path $PSScriptRoot 'rcon.ps1'

function Rcon([string]$command) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $rconPs1 $command 2>&1
}

function Start-Dev {
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $startPs1 `
                        -NoDeploy -Detached -WaitReady
    $match = [regex]::Match(($out -join "`n"), 'PID=(\d+)')
    if (-not $match.Success) {
        Write-Host "could not start the server:" -ForegroundColor Red
        Write-Host ($out -join "`n")
        return $null
    }
    return [int]$match.Groups[1].Value
}

# Largest committed sequence the CURRENT log claims. Read while latest.log still
# belongs to the run that was killed - the next boot rotates it away.
function Get-MaxCommitted {
    if (-not (Test-Path $log)) { return 0 }
    $max = 0
    foreach ($line in (Get-Content $log -ErrorAction SilentlyContinue)) {
        $m = [regex]::Match($line, '\[PROBE\] COMMIT (\d+)')
        if ($m.Success) {
            $value = [long]$m.Groups[1].Value
            if ($value -gt $max) { $max = $value }
        }
    }
    return $max
}

function Get-Recovered {
    if (-not (Test-Path $log)) { return -1 }
    $found = -1
    foreach ($line in (Get-Content $log -ErrorAction SilentlyContinue)) {
        $m = [regex]::Match($line, '\[PROBE\] RECOVERED (\d+)')
        if ($m.Success) { $found = [long]$m.Groups[1].Value }
    }
    return $found
}

Write-Host ""
Write-Host "  THE STILL - Phase 0 gate" -ForegroundColor White
Write-Host "  $Rounds rounds of kill -9 at random moments" -ForegroundColor DarkGray
Write-Host ""

# ---- bring one server up and arm the probe -----------------------------
Write-Host "  starting server..." -NoNewline
$serverPid = Start-Dev
if (-not $serverPid) { exit 1 }
Write-Host " up (pid $serverPid)"

# Persisted to dials.yml, so every later boot in this run arms itself.
Rcon "still dial set probe.enabled true" | Out-Null
Rcon "still probe reset" | Out-Null
Rcon "still probe start" | Out-Null
Write-Host "  probe armed"
Write-Host ""

$results = @()
$passes = 0

for ($round = 1; $round -le $Rounds; $round++) {
    $runSeconds = Get-Random -Minimum $MinRunSeconds -Maximum ($MaxRunSeconds + 1)
    Write-Host ("  round {0,2}/{1}  running {2,2}s ..." -f $round, $Rounds, $runSeconds) -NoNewline
    Start-Sleep -Seconds $runSeconds

    # Kill first, then read: everything log4j managed to flush before the
    # process died counts, which makes the asserted set as large as possible.
    Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 1500
    $acknowledged = Get-MaxCommitted

    $serverPid = Start-Dev
    if (-not $serverPid) {
        Write-Host " SERVER FAILED TO RESTART" -ForegroundColor Red
        $results += [pscustomobject]@{ Round = $round; Ack = $acknowledged; Recovered = -1; Pass = $false }
        break
    }
    $recovered = Get-Recovered

    $ok = ($acknowledged -gt 0) -and ($recovered -ge $acknowledged)
    if ($ok) { $passes++ }
    $results += [pscustomobject]@{
        Round = $round; Ack = $acknowledged; Recovered = $recovered; Pass = $ok
    }

    if ($ok) {
        Write-Host (" killed at {0}, recovered {1}  PASS" -f $acknowledged, $recovered) -ForegroundColor Green
    } elseif ($acknowledged -le 0) {
        Write-Host " NO WRITES ACKNOWLEDGED - probe was not running" -ForegroundColor Red
    } else {
        Write-Host (" killed at {0}, recovered only {1}  LOST {2}" -f `
                    $acknowledged, $recovered, ($acknowledged - $recovered)) -ForegroundColor Red
    }

    Rcon "still probe start" | Out-Null
}

# ---- report -------------------------------------------------------------
Write-Host ""
Write-Host "  ------------------------------------------------" -ForegroundColor DarkGray
$results | Format-Table -AutoSize
if ($passes -eq $Rounds) {
    Write-Host "  GATE PASSED - $passes/$Rounds rounds, zero acknowledged writes lost." -ForegroundColor Green
    Write-Host ""
    $exit = 0
} else {
    Write-Host "  GATE FAILED - $passes/$Rounds rounds passed." -ForegroundColor Red
    Write-Host "  You are not past Phase 0." -ForegroundColor Red
    Write-Host ""
    $exit = 1
}

Write-Host "  leaving the server running (pid $serverPid). Stop it with:" -ForegroundColor DarkGray
Write-Host "    Stop-Process -Id $serverPid -Force" -ForegroundColor DarkGray
Write-Host ""
exit $exit
