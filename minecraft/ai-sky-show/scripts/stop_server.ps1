<#
    Stop the show server cleanly: RCON `stop` (Paper saves worlds, SkyClone saves profiles at
    shutdown), then wait. Only if it is still alive after -TimeoutSec is it killed - and only the
    process whose command line carries -Daisky.show=true (never SkyClone's dev server, never a client).

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop_server.ps1
        ... -TimeoutSec 90
        ... -NoKill        never force-kill; exit 1 if it does not stop in time
#>
[CmdletBinding()]
param(
    [int]$TimeoutSec = 90,
    [switch]$NoKill
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_common.ps1')

$procs = @(Get-ShowServerProcess)
if ($procs.Count -eq 0) { Write-Host "The show server is not running."; exit 0 }

$r = Invoke-ShowRcon 'stop' 10
if ($r.Ok) { Write-Host "Sent 'stop' over RCON; waiting for the server to save and exit..." }
else { Write-Host "RCON did not answer ($($r.Output)); waiting anyway..." -ForegroundColor Yellow }

$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    if (@(Get-ShowServerProcess).Count -eq 0) { Write-Host "Show server stopped." -ForegroundColor Green; exit 0 }
    Start-Sleep -Seconds 1
}
if ($NoKill) { Write-Host "Still running after ${TimeoutSec}s (not killing: -NoKill)." -ForegroundColor Red; exit 1 }
foreach ($p in @(Get-ShowServerProcess)) {
    Write-Host "Still running after ${TimeoutSec}s - killing pid $($p.ProcessId) (the show server only)." -ForegroundColor Yellow
    Stop-Process -Id $p.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 2
if (@(Get-ShowServerProcess).Count -eq 0) { exit 0 }
Write-Host "Could not stop the show server." -ForegroundColor Red
exit 1
