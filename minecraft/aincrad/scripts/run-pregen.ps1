# Runs the Aincrad dev server and drives full Floor One terrain pregeneration to completion.
#
# Resumable: the driver stores a cursor in the generation ledger, so stopping this script and
# re-running it continues from where it left off. Chunks already committed - notably the entire
# Town review slice - are skipped rather than re-shaped.
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $ProjectRoot "server"
$LogFile = Join-Path $ServerDir "pregen-run.log"

if (-not (Test-Path -LiteralPath (Join-Path $ServerDir ".aincrad-first-boot-ready"))) {
    Write-Host "First boot is still locked. Complete server\README-FIRST-BOOT.md first." -ForegroundColor Yellow
    exit 1
}

$paperJar = Get-ChildItem -LiteralPath $ServerDir -Filter "paper-*.jar" -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $paperJar) {
    Write-Host "No paper-*.jar found in $ServerDir" -ForegroundColor Yellow
    exit 1
}

# Refuse to start a second server on the same world - Minecraft holds a session lock and the
# second process dies with "another process has locked a portion of the file".
$existing = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$($paperJar.Name)*" }
if ($existing) {
    Write-Host "A server is already running on this world (PID $($existing.ProcessId))." -ForegroundColor Yellow
    Write-Host "Stop it before starting pregeneration." -ForegroundColor Yellow
    exit 1
}

$javaExe = Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe"
if (-not (Test-Path -LiteralPath $javaExe)) {
    $javaExe = (Get-Command java.exe).Source
}

# The StreamWriter behind a redirected stdin emits a UTF-8 BOM (EF BB BF) ahead of the first byte the
# child receives, and the Minecraft console treats it as part of the command name, so the first
# command sent always dies with "Unknown or incomplete command: <BOM>aincrad ...". That was invisible
# here only because the first command happened to be a status query nobody checked the result of.
# Writing raw bytes to .BaseStream does not help, and ProcessStartInfo.StandardInputEncoding does not
# exist on Windows PowerShell's .NET Framework - so the BOM is absorbed by an explicit sacrificial
# line instead of by accident.
function Send-Console {
    param($proc, [string]$line)
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($line + "`n")
    $proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $proc.StandardInput.BaseStream.Flush()
}

Write-Host "Server : $ServerDir"
Write-Host "Paper  : $($paperJar.Name)"
Write-Host "Log    : $LogFile"
Write-Host ""
Write-Host "Starting. Progress lines appear every 2000 chunks." -ForegroundColor Cyan
Write-Host ""

# 8 chunks/tick, limit 0 = run to completion. Console input is piped so the run is unattended.
$commands = @(
    'aincrad pregen status',
    'aincrad pregen start 8 0'
)

Push-Location $ServerDir
try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $javaExe
    $psi.Arguments = "-Xms2G -Xmx8G -jar `"$($paperJar.Name)`" nogui"
    $psi.WorkingDirectory = $ServerDir
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)

    # Mirror console output to the log file so readiness can be detected and progress reviewed.
    if (Test-Path -LiteralPath $LogFile) { Remove-Item -LiteralPath $LogFile -Force }
    $reader = Start-Job -ScriptBlock {
        param($p, $log)
        while (-not $p.HasExited -or -not $p.StandardOutput.EndOfStream) {
            $line = $p.StandardOutput.ReadLine()
            if ($null -ne $line) { Add-Content -LiteralPath $log -Value $line }
        }
    } -ArgumentList $proc, $LogFile -ErrorAction SilentlyContinue

    # Wait for the server to actually report ready, then add a margin. A fixed sleep is not safe:
    # boot time grows as the world grows (10s empty, ~36s once the first chunks exist), and a
    # command delivered at the exact instant the console becomes ready hits a Paper startup race
    # where the console command source has no level yet and the command dies with an NPE.
    $ready = $false
    for ($i = 0; $i -lt 180; $i++) {
        Start-Sleep -Seconds 2
        if ((Test-Path -LiteralPath $LogFile) -and
            (Select-String -LiteralPath $LogFile -Pattern 'Done \(' -Quiet -ErrorAction SilentlyContinue)) {
            $ready = $true
            break
        }
        if ($proc.HasExited) { break }
    }
    if (-not $ready) {
        Write-Host "Server did not report ready; sending commands after a conservative wait." -ForegroundColor Yellow
    }
    Start-Sleep -Seconds 15

    Send-Console $proc 'list'      # sacrificial: absorbs the BOM described above
    Start-Sleep -Seconds 2
    foreach ($c in $commands) {
        Send-Console $proc $c
        Start-Sleep -Seconds 3
    }

    Write-Host "Pregeneration is running. Press Ctrl+C to stop safely (progress is saved)." -ForegroundColor Cyan
    $proc.WaitForExit()
}
finally {
    Pop-Location
    if ($proc -and -not $proc.HasExited) {
        try {
            Send-Console $proc 'aincrad pregen stop'
            Send-Console $proc 'save-all'
            Send-Console $proc 'stop'
            $proc.WaitForExit(120000) | Out-Null
        } catch { }
    }
}
