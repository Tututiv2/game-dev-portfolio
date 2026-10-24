<#
    Create (or refresh) AISkyShow\server\ - the show's own Paper server - as a COPY of SkyClone's dev
    server folder WITHOUT its worlds or any player data, then write the show's settings.

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup_server.ps1
        ... -Clean       also delete the show server's worlds, SkyClone DB and island state first
                         (a fully fresh world: islands rebuild on the next boot, which takes a while)
        ... -Force       run even while Streamlabs / Rocket League / OBS are open

    Copied from SkyClone's server (read-only source): paper-*.jar, libraries\, versions\, cache\,
    config\ (paper-global / paper-world-defaults), bukkit/spigot/commands/help/permissions yml, eula,
    plugins\SkyClone.jar (the LATEST build from SkyblockClone\plugin\build\libs when present),
    ViaVersion + ViaBackwards jars and their config folders.
    NEVER copied: world\, _old_*\, logs\, usercache/ops/whitelist/banned json, plugins\SkyClone\
    (skyclone.db, islands\, dials.yml, regen state), spark\, bStats\.

    Written: server.properties (port 25640, 127.0.0.1, offline mode, RCON 25641, whitelist, flight,
    no spawn protection, view/sim distance 6, motd), whitelist.json (cast + camera), ops.json
    (camera), plugins\ShowControl\config.yml (cast + camera), and ShowControl.jar if it is built.
    Re-running is safe: worlds and the DB of an existing show server are left alone unless -Clean.
#>
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$Force
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_common.ps1')

Assert-NotStreaming -Force:$Force

if (@(Get-ShowServerProcess).Count -gt 0) {
    Write-Host "The show server is running - stop it first (scripts\stop_server.ps1)." -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $TemplateDir)) { Write-Host "No SkyClone server template at $TemplateDir" -ForegroundColor Red; exit 1 }

New-Item -ItemType Directory -Force -Path $ServerDir | Out-Null
$plugins = Join-Path $ServerDir 'plugins'
New-Item -ItemType Directory -Force -Path $plugins | Out-Null

if ($Clean) {
    Write-Host "Clean: removing the show server's worlds and SkyClone state..." -ForegroundColor Yellow
    foreach ($p in @('world', 'logs', 'usercache.json')) {
        $t = Join-Path $ServerDir $p
        if (Test-Path $t) { Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue }
    }
    $sc = Join-Path $plugins 'SkyClone'
    if (Test-Path $sc) { Remove-Item $sc -Recurse -Force -ErrorAction SilentlyContinue }
    $st = Join-Path $plugins 'ShowControl\state.yml'
    if (Test-Path $st) { Remove-Item $st -Force -ErrorAction SilentlyContinue }
}

# ---- copy the runtime (robocopy: fast, skips unchanged files) ----------------------------------
function Copy-Dir([string]$name) {
    $src = Join-Path $TemplateDir $name
    if (-not (Test-Path $src)) { return }
    $dst = Join-Path $ServerDir $name
    & robocopy $src $dst /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { Write-Host "robocopy failed for $name (code $LASTEXITCODE)" -ForegroundColor Red; exit 1 }
}
Write-Host "Copying the Paper runtime from $TemplateDir ..."
foreach ($d in @('libraries', 'versions', 'cache', 'config')) { Copy-Dir $d }

$paper = Get-ChildItem $TemplateDir -Filter 'paper-*.jar' | Sort-Object Name -Descending | Select-Object -First 1
if (-not $paper) { Write-Host "No paper-*.jar in the template" -ForegroundColor Red; exit 1 }
Get-ChildItem $ServerDir -Filter 'paper-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne $paper.Name } |
    ForEach-Object { Remove-Item $_.FullName -Force }
Copy-Item $paper.FullName (Join-Path $ServerDir $paper.Name) -Force

foreach ($f in @('bukkit.yml', 'spigot.yml', 'commands.yml', 'help.yml', 'permissions.yml', 'eula.txt')) {
    $src = Join-Path $TemplateDir $f
    if (Test-Path $src) { Copy-Item $src (Join-Path $ServerDir $f) -Force }
}
Write-Utf8NoBom (Join-Path $ServerDir 'eula.txt') "eula=true`n"

# ---- plugins ----------------------------------------------------------------------------------
$built = Join-Path $SkyCloneRoot 'plugin\build\libs\SkyClone.jar'
$tplJar = Join-Path $TemplateDir 'plugins\SkyClone.jar'
$sky = $null
if (Test-Path $built) { $sky = Get-Item $built }
if ((Test-Path $tplJar) -and ((-not $sky) -or ((Get-Item $tplJar).LastWriteTime -gt $sky.LastWriteTime))) { $sky = Get-Item $tplJar }
if (-not $sky) { Write-Host "No SkyClone.jar found (built or in the template)" -ForegroundColor Red; exit 1 }
Copy-Item $sky.FullName (Join-Path $plugins 'SkyClone.jar') -Force
Write-Host ("SkyClone.jar <- {0} ({1:N0} KB, {2})" -f $sky.FullName, ($sky.Length / 1KB), $sky.LastWriteTime)

foreach ($pat in @('ViaVersion-*.jar', 'ViaBackwards-*.jar')) {
    $j = Get-ChildItem (Join-Path $TemplateDir 'plugins') -Filter $pat -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($j) {
        Get-ChildItem $plugins -Filter $pat -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne $j.Name } | ForEach-Object { Remove-Item $_.FullName -Force }
        Copy-Item $j.FullName (Join-Path $plugins $j.Name) -Force
    }
}
foreach ($cfgDir in @('ViaVersion', 'ViaBackwards')) {
    $src = Join-Path $TemplateDir "plugins\$cfgDir"
    if (Test-Path $src) {
        & robocopy $src (Join-Path $plugins $cfgDir) *.yml /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
    }
}

$showJar = Join-Path $PluginDir 'build\libs\ShowControl.jar'
if (Test-Path $showJar) {
    Copy-Item $showJar (Join-Path $plugins 'ShowControl.jar') -Force
    Write-Host "ShowControl.jar copied."
} else {
    Write-Host "ShowControl.jar is not built yet - run scripts\deploy_plugin.ps1 next." -ForegroundColor Yellow
}

# ---- server.properties ------------------------------------------------------------------------
$props = [ordered]@{}
$tplProps = Join-Path $TemplateDir 'server.properties'
$existing = Join-Path $ServerDir 'server.properties'
$srcProps = $tplProps
if (Test-Path $existing) { $srcProps = $existing }
foreach ($line in [System.IO.File]::ReadAllLines($srcProps, [System.Text.Encoding]::UTF8)) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $i = $line.IndexOf('=')
    $props[$line.Substring(0, $i)] = $line.Substring($i + 1)
}
$override = [ordered]@{
    'server-port'                = "$GamePort"
    'server-ip'                  = '127.0.0.1'
    'online-mode'                = 'false'
    'enforce-secure-profile'     = 'false'
    'enable-rcon'                = 'true'
    'rcon.port'                  = "$RconPort"
    'rcon.password'              = $RconPassword
    'broadcast-rcon-to-ops'      = 'false'
    'white-list'                 = 'true'
    'enforce-whitelist'          = 'true'
    'allow-flight'               = 'true'
    'spawn-protection'           = '0'
    'view-distance'              = '6'
    'simulation-distance'        = '6'
    'max-players'                = '10'
    'pvp'                        = 'false'
    'level-name'                 = 'world'
    'management-server-enabled'  = 'false'
    'motd'                       = '§b§lCan 4 AIs Beat SkyBlock? §8- show server'
}
foreach ($k in $override.Keys) { $props[$k] = $override[$k] }
$props.Remove('management-server-secret')
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('#AISkyShow show server - written by scripts/setup_server.ps1 (re-run it rather than editing)')
foreach ($k in $props.Keys) { [void]$sb.AppendLine("$k=$($props[$k])") }
Write-Utf8NoBom (Join-Path $ServerDir 'server.properties') $sb.ToString()

# ---- whitelist / ops ----------------------------------------------------------------------------
$camera = Get-CameraAccount
$accounts = @(Get-CastAccounts) + @($camera)
$wl = @()
foreach ($a in $accounts) { $wl += [ordered]@{ uuid = (Get-OfflineUuid $a); name = $a } }
Write-Utf8NoBom (Join-Path $ServerDir 'whitelist.json') (ConvertTo-Json @($wl) -Depth 3)
$ops = @([ordered]@{ uuid = (Get-OfflineUuid $camera); name = $camera; level = 4; bypassesPlayerLimit = $false })
Write-Utf8NoBom (Join-Path $ServerDir 'ops.json') (ConvertTo-Json @($ops) -Depth 3)

# ---- ShowControl config -------------------------------------------------------------------------
$scDir = Join-Path $plugins 'ShowControl'
New-Item -ItemType Directory -Force -Path $scDir | Out-Null
$castList = (Get-CastAccounts | ForEach-Object { $_ }) -join ', '
$cfg = @"
# ShowControl settings - written by scripts/setup_server.ps1 from config/show.json + config/cast.json.
http:
  host: 127.0.0.1
  port: $HttpPort
  ring-size: 5000
  heartbeat-seconds: 10
cast: [$castList]
camera: $camera
feed:
  gather-window-seconds: 30
  near-death-fraction: 0.15
  near-death-debounce-seconds: 20
  big-hit-fraction: 0.30
  big-hit-debounce-seconds: 5
  ability-debounce-seconds: 8
  coin-milestones: [1000, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000, 5000000, 10000000]
  state-radius: 24
  snapshot-ticks: 10
freeze-world-ticks: true
cmd-sender: feedback
allow-test-commands: true
"@
Write-Utf8NoBom (Join-Path $scDir 'config.yml') $cfg
$rules = Join-Path $PluginDir 'src\main\resources\rulesets.yml'
if (Test-Path $rules) { Copy-Item $rules (Join-Path $scDir 'rulesets.yml') -Force }

Write-Host ""
Write-Host "Show server ready at $ServerDir" -ForegroundColor Green
Write-Host "  game  127.0.0.1:$GamePort (offline mode, whitelist: $($accounts -join ', '))"
Write-Host "  rcon  127.0.0.1:$RconPort    ShowControl HTTP 127.0.0.1:$HttpPort"
Write-Host "  next: scripts\deploy_plugin.ps1  then  scripts\start_server.ps1 -Detached -WaitReady"
exit 0
