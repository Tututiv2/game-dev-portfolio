@echo off
setlocal
set "EXE=%~dp0SHADOW_DESIGNER\release\Shadow-Designer.exe"

if exist "%EXE%" (
  start "" "%EXE%"
  exit /b 0
)

echo Shadow Designer executable not found.
echo Build it first with Build-Shadow-Designer.bat
pause
exit /b 1
