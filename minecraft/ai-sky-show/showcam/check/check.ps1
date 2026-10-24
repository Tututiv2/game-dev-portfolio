# Headless check of ShowCam's pure camera maths (CamMath / ShotSpec / ShotPlanner).
# No Minecraft, no Fabric, no gradle: one javac + one java process, a few seconds.
# Safe to run while streaming.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src  = Join-Path $here '..\src\main\java\com\aisky\showcam'
$out  = Join-Path $env:TEMP 'showcam-check'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

$files = @(
    (Join-Path $src 'CamMath.java'),
    (Join-Path $src 'ShotSpec.java'),
    (Join-Path $src 'ShotPlanner.java'),
    (Join-Path $here 'CamMathCheck.java')
)
& javac --release 25 -encoding UTF-8 -d $out @files
if ($LASTEXITCODE -ne 0) { Write-Host 'compile failed'; exit 1 }
& java -cp $out CamMathCheck
exit $LASTEXITCODE
