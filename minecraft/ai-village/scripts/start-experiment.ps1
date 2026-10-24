# Starts the AI Village experiment: Paper 26.1.2 server + Mindcraft bots + in-game controller.
$root = Split-Path $PSScriptRoot -Parent
$serverDir = Join-Path $root "server"
$mindcraftDir = Join-Path $root "mindcraft"
$controlDir = Join-Path $root "control"

# 1. Start Paper server if port 55916 is not already listening
$listening = Get-NetTCPConnection -LocalPort 55916 -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    Write-Host "Starting Paper 26.1.2 server..." -ForegroundColor Cyan
    Start-Process -FilePath "java" -ArgumentList "-Dvillage.experiment=true","-Xms2G","-Xmx4G","-jar","paper.jar","nogui" -WorkingDirectory $serverDir -WindowStyle Minimized
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Get-NetTCPConnection -LocalPort 55916 -State Listen -ErrorAction SilentlyContinue) { break }
    }
    if (-not (Get-NetTCPConnection -LocalPort 55916 -State Listen -ErrorAction SilentlyContinue)) {
        Write-Host "Server failed to start within 3 minutes. Check $serverDir\logs\latest.log" -ForegroundColor Red
        pause; exit 1
    }
    Write-Host "Server is up." -ForegroundColor Green
} else {
    Write-Host "Server already running." -ForegroundColor Green
}

# 2. Make sure Ollama is up (it normally autostarts with Windows)
try { Invoke-RestMethod "http://localhost:11434/api/tags" -TimeoutSec 3 | Out-Null }
catch {
    Write-Host "Starting Ollama..." -ForegroundColor Cyan
    Start-Process "ollama" -ArgumentList "serve" -WindowStyle Hidden
    Start-Sleep -Seconds 5
}

# 3. Start the in-game controller (!sim commands) if not already running
$ctl = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" | Where-Object { $_.CommandLine -like "*village-control.js*" }
if (-not $ctl) {
    Write-Host "Starting village controller..." -ForegroundColor Cyan
    Start-Process -FilePath "node" -ArgumentList "village-control.js" -WorkingDirectory $controlDir -WindowStyle Minimized
}

# 4. Start Mindcraft bots if not already running
$bots = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" | Where-Object { $_.CommandLine -like "*main.js*" -and $_.CommandLine -notlike "*village-control*" }
if (-not $bots) {
    Write-Host "Starting bots..." -ForegroundColor Cyan
    Start-Process -FilePath "node" -ArgumentList "main.js" -WorkingDirectory $mindcraftDir -WindowStyle Minimized
}

Write-Host ""
Write-Host "AI Village is running!" -ForegroundColor Green
Write-Host "  Web UI:      http://localhost:8080"
Write-Host "  Join at:     localhost:55916 (any Minecraft client version - ViaVersion handles it)"
Write-Host "  In-game:     type '!sim help' in chat for simulation commands, '!sim op' to op yourself"
