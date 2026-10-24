<#
    Shared helpers for the AISkyShow server scripts. Dot-source it:  . (Join-Path $PSScriptRoot '_common.ps1')
    PowerShell 5.1 compatible (no ?:, no ??, no &&).
#>

$script:ShowRoot   = Split-Path -Parent $PSScriptRoot
$script:ServerDir  = Join-Path $ShowRoot 'server'
$script:PluginDir  = Join-Path $ShowRoot 'plugin'
$script:ConfigDir  = Join-Path $ShowRoot 'config'
$script:ShowMarker = '-Daisky.show=true'

function Read-ShowJson([string]$name) {
    $p = Join-Path $ConfigDir $name
    if (-not (Test-Path $p)) { throw "missing $p" }
    # Read as UTF-8 explicitly: PS 5.1 Get-Content defaults to the ANSI code page.
    $text = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
    return $text | ConvertFrom-Json
}

$script:ShowCfg = Read-ShowJson 'show.json'
$script:CastCfg = Read-ShowJson 'cast.json'
$script:GamePort = [int]$ShowCfg.ports.server
$script:RconPort = [int]$ShowCfg.ports.rcon
$script:HttpPort = [int]$ShowCfg.ports.showcontrol
$script:RconPassword = [string]$ShowCfg.rcon_password
$script:SkyCloneRoot = [string]$ShowCfg.paths.skyclone
$script:TemplateDir = [string]$ShowCfg.paths.skyclone_server_template

function Get-CastAccounts {
    $names = @()
    foreach ($c in $CastCfg.characters) { $names += [string]$c.account }
    return $names
}

function Get-CameraAccount { return [string]$CastCfg.camera_account }

function Write-Utf8NoBom([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# Java's UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8)) - the offline-mode uuid.
function Get-OfflineUuid([string]$name) {
    $md5 = [System.Security.Cryptography.MD5]::Create()
    $b = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$name"))
    $b[6] = ($b[6] -band 0x0f) -bor 0x30
    $b[8] = ($b[8] -band 0x3f) -bor 0x80
    $h = ($b | ForEach-Object { $_.ToString('x2') }) -join ''
    return '{0}-{1}-{2}-{3}-{4}' -f $h.Substring(0, 8), $h.Substring(8, 4), $h.Substring(12, 4), $h.Substring(16, 4), $h.Substring(20, 12)
}

# The owner may be live-streaming: heavy steps refuse while these run (CONTRACT section 0).
function Get-StreamingProcesses {
    $names = @('Streamlabs OBS', 'Streamlabs Desktop', 'RocketLeague', 'obs64')
    $found = @()
    foreach ($n in $names) {
        $p = Get-Process -Name $n -ErrorAction SilentlyContinue
        if ($p) { $found += $n }
    }
    return $found
}

function Assert-NotStreaming([switch]$Force) {
    $s = @(Get-StreamingProcesses)
    if ($s.Count -gt 0) {
        if ($Force) {
            Write-Host ("WARNING: running anyway while {0} is open (-Force)." -f ($s -join ', ')) -ForegroundColor Yellow
        } else {
            Write-Host ("Refusing: {0} is running (the owner may be streaming). Re-run after the stream, or pass -Force." -f ($s -join ', ')) -ForegroundColor Red
            exit 3
        }
    }
}

function Test-Java25([string]$exe) {
    if (-not $exe) { return $false }
    $out = & cmd /c "`"$exe`" -version 2>&1"
    return (($out | Out-String) -match 'version "25')
}

function Find-Java25 {
    if ($env:JAVA_HOME) {
        $cand = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if ((Test-Path $cand) -and (Test-Java25 $cand)) { return $cand }
    }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath -and (Test-Java25 $onPath.Source)) { return $onPath.Source }
    foreach ($r in @("$env:ProgramFiles\Eclipse Adoptium", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:USERPROFILE\.gradle\jdks")) {
        if (-not (Test-Path $r)) { continue }
        foreach ($j in (Get-ChildItem $r -Recurse -Filter java.exe -ErrorAction SilentlyContinue)) {
            if (Test-Java25 $j.FullName) { return $j.FullName }
        }
    }
    return $null
}

# Only the show server: java processes whose command line carries the marker.
function Get-ShowServerProcess {
    $procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($ShowMarker) })
    return $procs
}

function Invoke-ShowRcon([string]$command, [int]$timeoutSec = 5) {
    $py = Join-Path $PSScriptRoot 'rcon.py'
    $out = & py -3 $py --port $RconPort --password $RconPassword --timeout $timeoutSec --strip $command 2>&1
    return [pscustomobject]@{ Ok = ($LASTEXITCODE -eq 0); Output = ($out | Out-String).Trim() }
}

function Test-ShowRcon {
    $py = Join-Path $PSScriptRoot 'rcon.py'
    & py -3 $py --port $RconPort --password $RconPassword --timeout 2 --ping 2>&1 | Out-Null
    return ($LASTEXITCODE -eq 0)
}
