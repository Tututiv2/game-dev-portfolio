@echo off
rem ---------------------------------------------------------------
rem  SoundBoard - double-click this. Builds only if it has to.
rem ---------------------------------------------------------------
cd /d "%~dp0"

if exist "bin\SoundBoard.exe" goto run

echo Building SoundBoard for the first time...
dotnet build "app\SoundBoard.csproj" -c Release -v quiet --nologo
if errorlevel 1 (
    echo.
    echo Build failed. Run Build.bat to see the full output.
    pause
    exit /b 1
)

:run
start "" "bin\SoundBoard.exe"
