@echo off
cd /d "%~dp0"
echo Verifying the capture engine against your real audio hardware.
echo A quiet test tone will play through your speakers for a few seconds.
echo.

dotnet build selftest\SelfTest.csproj -c Release -v minimal
if errorlevel 1 goto fail

echo.
"selftest\bin\Release\net8.0-windows\SelfTest.exe"
echo.
pause
exit /b 0

:fail
echo.
echo Build failed. You need the .NET 8 SDK: https://dotnet.microsoft.com/download
echo.
pause
exit /b 1
