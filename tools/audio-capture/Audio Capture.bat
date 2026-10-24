@echo off
cd /d "%~dp0"
set "EXE=bin\Release\net8.0-windows\AudioCapture.exe"

if not exist "%EXE%" (
    echo First run - building Audio Capture...
    echo.
    dotnet build -c Release -v minimal
    if errorlevel 1 goto fail
)

start "" "%EXE%"
exit /b 0

:fail
echo.
echo Build failed. You need the .NET 8 SDK: https://dotnet.microsoft.com/download
echo.
pause
exit /b 1
