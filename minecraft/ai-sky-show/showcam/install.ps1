# ShowCam: build the jar and install it into the Fabric-26.1.2 launcher instance.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1            # build + install
#   powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 -NoBuild   # install the last build
#
# Refuses to run while the owner is streaming or gaming (Streamlabs / Rocket League / OBS),
# because a gradle build is minutes of CPU. -Force overrides that, and nothing else.
# Installs ONLY into MinecraftLauncher\data\instances\Fabric-26.1.2\mods - never %APPDATA%\.minecraft.
param(
    [switch]$NoBuild,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$busy = @(Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -match '^(Streamlabs.*|RocketLeague|obs64)$' } |
    Select-Object -ExpandProperty ProcessName -Unique)
if ($busy.Count -gt 0 -and -not $Force) {
    Write-Host "Not now: $($busy -join ', ') running. Re-run after the stream (or pass -Force)." -ForegroundColor Yellow
    exit 2
}

$mods = Join-Path $here '..\..\MinecraftLauncher\data\instances\Fabric-26.1.2\mods'
if (-not (Test-Path $mods)) {
    Write-Host "Instance mods folder not found: $mods" -ForegroundColor Red
    exit 1
}
$mods = (Resolve-Path $mods).Path
if ($mods -like "*\.minecraft*") {
    Write-Host "Refusing: $mods is inside a .minecraft folder." -ForegroundColor Red
    exit 1
}

Push-Location $here
try {
    if (-not $NoBuild) {
        # --no-daemon: leave nothing running in the background afterwards.
        & .\gradlew.bat build --no-daemon
        if ($LASTEXITCODE -ne 0) {
            Write-Host "gradle build failed ($LASTEXITCODE)" -ForegroundColor Red
            exit $LASTEXITCODE
        }
    }
    $jar = Get-ChildItem (Join-Path $here 'build\libs') -Filter 'showcam-*+mc26.1.2.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-sources' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        Write-Host "No built jar in build\libs - run without -NoBuild first." -ForegroundColor Red
        exit 1
    }

    foreach ($old in Get-ChildItem $mods -Filter 'showcam-*.jar') {
        try {
            Remove-Item $old.FullName -Force
        } catch {
            Write-Host "Could not replace $($old.Name) - is a Minecraft client from this instance running? Close it and retry." -ForegroundColor Red
            exit 1
        }
    }
    Copy-Item $jar.FullName $mods
    Write-Host "Installed $($jar.Name) -> $mods" -ForegroundColor Green
    Get-ChildItem $mods -Filter '*.jar' | ForEach-Object { Write-Host "  mods\$($_.Name)" }
} finally {
    Pop-Location
}
