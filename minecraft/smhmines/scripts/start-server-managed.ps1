# Managed dev-server loop: starts Paper, and restarts it whenever a deploy drops the
# .restart flag into server/. Keep this window open while you work.
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\paper-process.ps1"

$flag = Get-RestartFlag
if (Test-Path -LiteralPath $flag) { Remove-Item -LiteralPath $flag -Force }

function Wait-PortFree {
    # Paper can hold 25600 for a few seconds after the process dies. Starting into a bound
    # port makes the new server exit immediately, which used to look like "it just didn't
    # start" with nothing in any log to explain it.
    param([int]$Port = 25600, [int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $inUse = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        if ($inUse.Count -eq 0) { return $true }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "[smhmines] WARNING: port $Port still bound after ${TimeoutSeconds}s." -ForegroundColor Yellow
    return $false
}

while ($true) {
    Wait-PortFree | Out-Null

    try {
        $proc = Start-PaperServerProcess
    } catch {
        Write-Host "[smhmines] FAILED to start: $($_.Exception.Message)" -ForegroundColor Red
        break
    }

    $restartWanted = $false
    while (-not $proc.HasExited) {
        if (Test-Path -LiteralPath $flag) {
            Write-Host "[smhmines] restart flag seen - cycling server..." -ForegroundColor Cyan
            Remove-Item -LiteralPath $flag -Force -ErrorAction SilentlyContinue
            Stop-PaperServerProcesses
            Wait-PaperServerDown -TimeoutSeconds 20 | Out-Null
            $restartWanted = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if ($restartWanted) { continue }

    # The process ended on its own. If a flag landed during shutdown, honour it; otherwise
    # this was a real stop (or a crash) and the loop is done.
    if (Test-Path -LiteralPath $flag) {
        Remove-Item -LiteralPath $flag -Force -ErrorAction SilentlyContinue
        continue
    }

    $code = try { $proc.ExitCode } catch { "unknown" }
    Write-Host "[smhmines] server exited (code $code). Loop ending." -ForegroundColor Yellow
    break
}
