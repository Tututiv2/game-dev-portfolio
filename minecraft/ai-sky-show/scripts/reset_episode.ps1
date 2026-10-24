<#
    Give the four cast accounts brand-new SkyBlock profiles for a new episode.

    How SkyClone stores a player (read from its source, 2026-10-03):
      - profiles, purse, bank and every module's per-player data: SQLite, plugins\SkyClone\skyclone.db
        (tables profiles / profile_members / profile_member_data / profile_shared_data)
      - the vanilla inventory + ender chest: world\players\data\<uuid>.dat
      - islands: blocks in world\dimensions\minecraft\sb_*; a private island is an INSTANCE keyed by
        profile id, so a new profile simply gets a new island slot.
    So a reset does NOT need to delete worlds (the hub alone takes minutes to rebuild): it closes the
    cast's profile memberships (SkyClone's own "left profile" state - its next login creates a fresh
    first profile) and deletes their vanilla player files.

    Modes:
        (default)  stop -> scripts\reset_profiles.py -> start -Detached -WaitReady      (authoritative)
        -Live      no restart: RCON `show reset` (kicks the cast, closes memberships through SkyClone's
                   DB thread, wipes their inventory on rejoin). Fast; fine between takes.
        -Full      stop -> delete the show server's worlds + SkyClone DB + island state -> start.
                   Everything rebuilds from SkyClone's generators on boot (slow). Use when the world
                   itself is damaged.
        -NoStart   leave the server stopped afterwards (default/-Full modes).

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\reset_episode.ps1
        ... -Live
        ... -Full -Force
#>
[CmdletBinding()]
param(
    [switch]$Live,
    [switch]$Full,
    [switch]$NoStart,
    [switch]$Force
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_common.ps1')

if ($Live) {
    $r = Invoke-ShowRcon 'show reset' 10
    Write-Host $r.Output
    if (-not $r.Ok) { Write-Host "RCON failed - is the show server running?" -ForegroundColor Red; exit 1 }
    exit 0
}

Assert-NotStreaming -Force:$Force

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'stop_server.ps1')
if ($LASTEXITCODE -ne 0) { Write-Host "Could not stop the server; reset aborted." -ForegroundColor Red; exit 1 }

if ($Full) {
    Write-Host "Full reset: deleting worlds, SkyClone DB and island state of the SHOW server..." -ForegroundColor Yellow
    foreach ($p in @('world')) {
        $t = Join-Path $ServerDir $p
        if (Test-Path $t) { Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue }
    }
    $sc = Join-Path $ServerDir 'plugins\SkyClone'
    foreach ($p in @('skyclone.db', 'skyclone.db-wal', 'skyclone.db-shm', 'islands', 'farming-contest.json', 'farming-regrowth.json', 'foraging-regen.json')) {
        $t = Join-Path $sc $p
        if (Test-Path $t) { Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue }
    }
} else {
    $py = Join-Path $PSScriptRoot 'reset_profiles.py'
    & py -3 $py --server $ServerDir
    if ($LASTEXITCODE -ne 0) { Write-Host "reset_profiles.py failed; server left stopped." -ForegroundColor Red; exit 1 }
}

# The live-reset inventory wipe list is moot after an offline reset (the player files are gone).
$pending = Join-Path $ServerDir 'plugins\ShowControl\pending-wipe.yml'
if (Test-Path $pending) { Remove-Item $pending -Force -ErrorAction SilentlyContinue }

if ($NoStart) { Write-Host "Reset done; server left stopped." -ForegroundColor Green; exit 0 }
$startArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'start_server.ps1'), '-Detached', '-WaitReady')
if ($Force) { $startArgs += '-Force' }   # -File mode cannot bind '-Force:$false', so pass it only when set
& powershell @startArgs
exit $LASTEXITCODE
