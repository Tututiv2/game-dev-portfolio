# Starts the standalone SAO Prison Paper dev server after the guarded first-boot setup.
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $ProjectRoot "server"
$Checklist = Join-Path $ServerDir "README-FIRST-BOOT.md"
$ReadyMarker = Join-Path $ServerDir ".aincrad-first-boot-ready"
$PropertiesFile = Join-Path $ServerDir "server.properties"
$EulaFile = Join-Path $ServerDir "eula.txt"

function Stop-WithMessage {
    param([string[]]$Lines)

    Write-Host ""
    foreach ($line in $Lines) {
        Write-Host $line -ForegroundColor Yellow
    }
    Write-Host ""
    exit 1
}

function Test-Java25 {
    param([string]$JavaExe)

    if (-not $JavaExe -or -not (Test-Path -LiteralPath $JavaExe)) {
        return $false
    }

    # Use `--version` (JDK 9+), which writes to STDOUT. The legacy `-version` writes to STDERR, and
    # `& java -version 2>&1` in Windows PowerShell wraps each stderr line in a NativeCommandError -
    # which, with $ErrorActionPreference = "Stop" set at the top of this script, aborted the whole
    # launcher even though Java was present and correct. That is exactly how this failed on the
    # first run where the first-boot guard actually passed.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $JavaExe --version) | Out-String
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
            return $false
        }
        # Matches both `openjdk 25.0.3 ...` and the older `... version "25.0.3"` shapes.
        return ($output -match '(^|\s)25(\.|\s|$)') -or ($output -match 'version "25')
    }
    catch {
        return $false
    }
    finally {
        $ErrorActionPreference = $prev
    }
}

function Find-Java25 {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\java.exe"))
    }

    $pathJava = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($pathJava) {
        $candidates.Add($pathJava.Source)
    }

    $searchRoots = @(
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium"),
        (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
        (Join-Path $env:ProgramFiles "Java"),
        (Join-Path $env:USERPROFILE ".gradle\jdks")
    )

    foreach ($searchRoot in $searchRoots) {
        if (-not (Test-Path -LiteralPath $searchRoot)) {
            continue
        }

        Get-ChildItem -LiteralPath $searchRoot -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Java25 $candidate) {
            return $candidate
        }
    }

    return $null
}

if (-not (Test-Path -LiteralPath $ServerDir -PathType Container)) {
    Stop-WithMessage @(
        "The server folder does not exist yet. Nothing has been generated.",
        "Create the Paper server only after following server\README-FIRST-BOOT.md."
    )
}

if (-not (Test-Path -LiteralPath $ReadyMarker -PathType Leaf)) {
    Stop-WithMessage @(
        "FIRST BOOT IS LOCKED. No world was started.",
        "Complete: $Checklist",
        "Only then create: $ReadyMarker"
    )
}

$paperJar = Get-ChildItem -LiteralPath $ServerDir -Filter "paper-*.jar" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $paperJar) {
    Stop-WithMessage @(
        "No paper-*.jar was found in:",
        "  $ServerDir",
        "Install the exact Paper build selected for this project before starting."
    )
}

if (-not (Test-Path -LiteralPath $PropertiesFile -PathType Leaf)) {
    Stop-WithMessage @("Missing server.properties. First boot remains locked.")
}

$properties = Get-Content -LiteralPath $PropertiesFile
$portLine = $properties | Where-Object { $_ -match '^server-port=' } | Select-Object -First 1
if ($portLine -ne "server-port=25577") {
    Stop-WithMessage @(
        "server.properties must contain: server-port=25577",
        "Current value: $portLine"
    )
}

if (-not (Test-Path -LiteralPath $EulaFile -PathType Leaf)) {
    Stop-WithMessage @(
        "Missing eula.txt.",
        "Read Mojang's EULA and set eula=true yourself before starting."
    )
}

$eulaAccepted = Get-Content -LiteralPath $EulaFile |
    Where-Object { $_ -match '^eula=true$' } |
    Select-Object -First 1
if (-not $eulaAccepted) {
    Stop-WithMessage @(
        "Mojang EULA has not been accepted.",
        "Read it and set eula=true yourself before starting."
    )
}

$javaExe = Find-Java25
if (-not $javaExe) {
    Stop-WithMessage @(
        "Java 25 is required for the selected Minecraft/Paper 26.1 development server.",
        "Install Temurin 25 and reopen this launcher."
    )
}

Write-Host "Starting SAO Prison dev server..." -ForegroundColor Cyan
Write-Host "Server: $ServerDir" -ForegroundColor DarkGray
Write-Host "Paper:  $($paperJar.Name)" -ForegroundColor DarkGray
Write-Host "Java:   $javaExe" -ForegroundColor DarkGray
Write-Host "Port:   25577" -ForegroundColor DarkGray
Write-Host ""

Push-Location $ServerDir
try {
    & $javaExe "-Xms2G" "-Xmx8G" "-jar" $paperJar.Name "nogui"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
