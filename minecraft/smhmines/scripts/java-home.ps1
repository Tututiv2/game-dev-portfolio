# Finds Java 25, required by Minecraft 26.1 / Paper 26.1.2.
# Safe to dot-source: defines functions only, never exits the caller.

function Test-JavaVersion {
    param([string]$JavaExe)
    if (-not (Test-Path $JavaExe)) { return $false }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = cmd /c "`"$JavaExe`" -version 2>&1"
    $ErrorActionPreference = $prev
    return ($output | Out-String) -match 'version "25'
}

function Find-Java25 {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-JavaVersion $candidate) { return $candidate }
    }

    $searchRoots = @(
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:USERPROFILE\.gradle\jdks"
    )

    foreach ($root in $searchRoots) {
        if (-not (Test-Path $root)) { continue }
        $javaExes = Get-ChildItem -Path $root -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue
        foreach ($java in $javaExes) {
            if (Test-JavaVersion $java.FullName) { return $java.FullName }
        }
    }

    return $null
}

function Show-Java25Help {
    Write-Host "Java 25 is required for Minecraft 26.1." -ForegroundColor Red
    Write-Host ""
    Write-Host "Install it with:" -ForegroundColor Yellow
    Write-Host "  winget install EclipseAdoptium.Temurin.25.JDK"
    Write-Host ""
    Write-Host "Or download from:" -ForegroundColor Yellow
    Write-Host "  https://adoptium.net/temurin/releases/?version=25"
    Write-Host ""
    Write-Host "During install, check:" -ForegroundColor Yellow
    Write-Host "  - Modify PATH variable"
    Write-Host "  - Set or override JAVA_HOME variable"
    Write-Host ""
    Write-Host "Then close and reopen PowerShell." -ForegroundColor Yellow
}

# Throws (rather than exits) so a dot-sourcing caller can decide what to do.
function Get-Java25Exe {
    $java25 = Find-Java25
    if (-not $java25) {
        Show-Java25Help
        throw "Java 25 not found."
    }
    return $java25
}
