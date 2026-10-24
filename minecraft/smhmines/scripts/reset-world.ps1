# Stops the dev server and moves the current world aside so Paper regenerates a fresh one.
#
# You need this after changing world HEIGHT: the dimension-type datapack sets y -64..1983, and
# chunks written at a different height cannot be reused. The old world is MOVED, never deleted.
#
#   .\scripts\reset-world.ps1        (or double-click "Reset World.bat")
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\paper-process.ps1"

$root      = Get-ProjectRoot
$serverDir = Get-ServerDir
$worldDir  = Join-Path $serverDir "world"

# THE canonical datapack. It lives at the project root, NOT inside world/, because world/ gets
# moved away by this very script. An earlier version of this file embedded a second copy of the
# JSON inline - and promptly overwrote a bug fix with its own stale copy. One source of truth.
$dpSource = Join-Path $root "datapacks"
if (-not (Test-Path -LiteralPath $dpSource)) { throw "Missing canonical datapacks/ at $dpSource" }

Write-Host "[reset] stopping the dev server..." -ForegroundColor Cyan
Stop-PaperServerProcesses
if (-not (Wait-PaperServerDown -TimeoutSeconds 25)) {
    throw "Server did not stop. Close the Dev Server window and try again."
}
Start-Sleep -Seconds 2   # let the OS release session.lock

if (Test-Path -LiteralPath $worldDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $leaf = "world-old-$stamp"
    Rename-Item -LiteralPath $worldDir -NewName $leaf
    Write-Host "[reset] old world moved to $leaf (not deleted)." -ForegroundColor Green
} else {
    Write-Host "[reset] no world/ to move." -ForegroundColor Yellow
}

# Copy the canonical datapack into the fresh world folder.
$dpDest = Join-Path $serverDir "world\datapacks"
New-Item -ItemType Directory -Force -Path $dpDest | Out-Null
Copy-Item -Path (Join-Path $dpSource "*") -Destination $dpDest -Recurse -Force
Write-Host "[reset] datapack copied from datapacks/ (y -64 .. 1983)." -ForegroundColor Green

Write-Host ""
Write-Host "[reset] Done. Now launch 'Dev Server.bat'." -ForegroundColor Cyan
