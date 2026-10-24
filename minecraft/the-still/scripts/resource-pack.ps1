# Build the resource pack, host it, and point the dev server at it.
#
# Ported from Shadow Prison's scripts/resource-pack.ps1. Three things in here are not
# obvious and all three cost that server real debugging time:
#
#   1. THE PROMPT IS RAW JSON. `resource-pack-prompt` in server.properties wants the raw
#      text component with no wrapping quotes and no backslash-escaping. Add either and
#      DedicatedServerProperties' JSON parser fails  --  and it fails SILENTLY, killing the
#      whole pack offer, not just the message. If the pack "stopped downloading" for no
#      reason, look here first.
#
#   2. THE PACK NEEDS A STABLE UUID. `resource-pack-id` is how the client knows an
#      offered pack is the same pack it already has. Regenerate it and every player
#      re-downloads 20 MB on their next join.
#
#   3. THE SHA1 IS NOT OPTIONAL. Without it the client will not cache the pack at all,
#      so every join is a fresh download. With a WRONG one the client rejects the pack
#      after downloading it, which looks exactly like a corrupt zip.
#
# Usage:  . scripts\resource-pack.ps1 ; Apply-DevServerResourcePack

$ErrorActionPreference = "Stop"

function Get-PackRoot    { Join-Path (Split-Path $PSScriptRoot -Parent) "resource-pack" }
function Get-PackServeDir { Join-Path (Split-Path $PSScriptRoot -Parent) "server\resourcepack" }
function Get-PackZipPath { Join-Path (Get-PackServeDir) "TheStill.zip" }
function Get-PackPidFile { Join-Path (Get-PackServeDir) ".http.pid" }

# Stable, forever. See note 2 above.
function Get-PackId { "8f2b1c4d-6e39-4a70-b5d8-1f0c7a935e42" }

function Get-PackPort { 8082 }   # Shadow Prison's dev host uses 8081; do not collide.

function Get-LanIPv4 {
    $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.IPAddress -notlike "169.254.*" -and
            $_.PrefixOrigin -ne "WellKnown"
        } | Select-Object -First 1 -ExpandProperty IPAddress
    if ($ip) { return $ip }
    return "127.0.0.1"
}

function Format-PackPrompt {
    param([string]$PlainText)
    # See note 1. Raw component, compressed, no outer quotes.
    if ($PlainText.TrimStart().StartsWith("{")) { return $PlainText.Trim() }
    return (@{ text = $PlainText; color = "gray" } | ConvertTo-Json -Compress)
}

function Build-Pack {
    $root = Get-PackRoot
    if (-not (Test-Path $root)) { throw "no resource-pack folder at $root" }
    $mcmeta = Join-Path $root "pack.mcmeta"
    if (-not (Test-Path $mcmeta)) { throw "resource-pack has no pack.mcmeta  --  the client will reject it outright" }

    $serve = Get-PackServeDir
    if (-not (Test-Path $serve)) { New-Item -ItemType Directory -Path $serve -Force | Out-Null }
    $zip = Get-PackZipPath
    if (Test-Path $zip) { Remove-Item $zip -Force }

    # Zip the CONTENTS of resource-pack/, not the folder itself. A pack whose
    # pack.mcmeta sits one directory down is silently invalid.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($root, $zip)

    $sha = (Get-FileHash -Path $zip -Algorithm SHA1).Hash.ToLower()
    $size = [math]::Round((Get-Item $zip).Length / 1MB, 2)
    Write-Host "pack built: $zip  (${size} MB)  sha1=$sha"
    return [pscustomobject]@{ Zip = $zip; Sha1 = $sha }
}

function Start-PackHost {
    # A pack has to be fetched over HTTP; the client will not read a file path.
    $serve = Get-PackServeDir
    $port  = Get-PackPort
    $pidFile = Get-PackPidFile

    if (Test-Path $pidFile) {
        $old = Get-Content $pidFile -ErrorAction SilentlyContinue
        if ($old) {
            $running = Get-Process -Id $old -ErrorAction SilentlyContinue
            if ($running) { Write-Host "pack host already up (pid $old, port $port)"; return }
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }

    $proc = Start-Process -FilePath "python" `
        -ArgumentList @("-m", "http.server", "$port", "--directory", "`"$serve`"") `
        -WindowStyle Hidden -PassThru
    Set-Content -Path $pidFile -Value $proc.Id -Encoding utf8
    Write-Host "pack host up: pid $($proc.Id), http://$(Get-LanIPv4):$port/TheStill.zip"
}

function Stop-PackHost {
    $pidFile = Get-PackPidFile
    if (-not (Test-Path $pidFile)) { return }
    $old = Get-Content $pidFile -ErrorAction SilentlyContinue
    if ($old) { Stop-Process -Id $old -Force -ErrorAction SilentlyContinue }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

function Set-ServerProperty {
    param([string]$Path, [string]$Key, [string]$Value)
    $lines = @(Get-Content $Path)
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*$([regex]::Escape($Key))\s*=") {
            $lines[$i] = "$Key=$Value"; $found = $true
        }
    }
    if (-not $found) { $lines += "$Key=$Value" }
    Set-Content -Path $Path -Value $lines -Encoding utf8
}

function Apply-DevServerResourcePack {
    $built = Build-Pack
    Start-PackHost

    $props = Join-Path (Split-Path $PSScriptRoot -Parent) "server\server.properties"
    if (-not (Test-Path $props)) { throw "no server.properties at $props  --  start the server once first" }

    $url = "http://$(Get-LanIPv4):$(Get-PackPort)/TheStill.zip"
    Set-ServerProperty $props "resource-pack"        $url
    Set-ServerProperty $props "resource-pack-sha1"   $built.Sha1
    Set-ServerProperty $props "resource-pack-id"     (Get-PackId)
    Set-ServerProperty $props "resource-pack-prompt" (Format-PackPrompt "THE STILL  --  moving text, and lamps that cut the fog.")
    # NOT required. A pack that kicks players who decline is a pack that kicks players.
    Set-ServerProperty $props "require-resource-pack" "false"

    Write-Host "server.properties pointed at $url"
    Write-Host "restart the server for it to offer the pack."
    return $built
}
