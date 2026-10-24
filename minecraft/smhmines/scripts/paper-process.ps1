# Shared Paper process helpers for the SmhMines dev server.
# Dot-source this: . "$PSScriptRoot\paper-process.ps1"

. "$PSScriptRoot\java-home.ps1"

# Unique marker passed to OUR JVM only, so process lookups can never match the sibling
# prison project's fourteen Paper servers. Changing this string breaks stop/restart.
$script:PaperMarker = "-Dsmhmines.devserver=true"

function Get-ProjectRoot { Split-Path -Parent $PSScriptRoot }
function Get-ServerDir   { Join-Path (Get-ProjectRoot) "server" }
function Get-PaperPidFile { Join-Path (Get-ServerDir) ".paper.pid" }
function Get-RestartFlag  { Join-Path (Get-ServerDir) ".restart" }

function Set-RestartFlag {
    Set-Content -Path (Get-RestartFlag) -Value "1" -Encoding utf8
}

function Get-PaperServerProcesses {
    $serverDir = Get-ServerDir
    $pidFile = Get-PaperPidFile
    if (Test-Path $pidFile) {
        $procId = (Get-Content $pidFile -Raw).Trim()
        if ($procId) {
            try {
                $p = Get-Process -Id ([int]$procId) -ErrorAction Stop
                # A stale pid file can point at a RECYCLED pid that now belongs to another
                # java process - quite possibly a prison server. Confirm the marker before
                # ever handing this process back to a caller that may kill it.
                $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
                if ($p.ProcessName -eq "java" -and $cim -and
                    $cim.CommandLine -match [regex]::Escape($script:PaperMarker)) {
                    return @($p)
                }
            } catch { }
        }
    }
    # Fallback when the pid file is missing or stale.
    #
    # DANGER, and the reason this matches on a marker and not on "paper-*.jar + nogui":
    # the sibling prison project runs FOURTEEN Paper servers on this machine (ports
    # 25565-25579). A generic match would return theirs too, and Stop-PaperServerProcesses
    # would kill every one of them on a deploy. Our JVM is launched with a unique
    # -D marker (see Start-PaperServerProcess) and ONLY that marker is matched here.
    try {
        $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
            Where-Object { $_.CommandLine -match [regex]::Escape($script:PaperMarker) }
        return @($procs | ForEach-Object {
            Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
        } | Where-Object { $_ })
    } catch { return @() }
}

function Stop-PaperServerProcesses {
    foreach ($p in Get-PaperServerProcesses) {
        try { Stop-Process -Id $p.Id -Force -ErrorAction Stop } catch { }
    }
    Remove-Item (Get-PaperPidFile) -ErrorAction SilentlyContinue
}

function Wait-PaperServerDown {
    param([int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-PaperServerProcesses)) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Wait-PaperServerUp {
    param([int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Get-PaperServerProcesses) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Start-PaperServerProcess {
    $serverDir = Get-ServerDir
    $jar = Get-ChildItem -Path $serverDir -Filter "paper-*.jar" -File |
           Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jar) { throw "No paper-*.jar found in $serverDir" }

    $javaExe = Get-Java25Exe
    Write-Host "[smhmines] starting $($jar.Name) with $javaExe"

    $proc = Start-Process -FilePath $javaExe `
        -ArgumentList @("-Xms1G", "-Xmx2G", $script:PaperMarker, "-jar", $jar.Name, "nogui") `
        -WorkingDirectory $serverDir `
        -PassThru `
        -NoNewWindow
    Set-Content -Path (Get-PaperPidFile) -Value $proc.Id -Encoding utf8
    return $proc
}
