@echo off
REM Copy the mod into Factorio's mods folder. Double-click after editing.
REM Factorio must be closed - it reads mods once, at startup.

setlocal
set SRC=%~dp0tutis-fun-mods
set DEST=%APPDATA%\Factorio\mods\tutis-fun-mods_0.1.0

echo Deploying Tuti's Fun Mods...
if exist "%DEST%" rmdir /s /q "%DEST%"
xcopy /e /i /q /y "%SRC%" "%DEST%" >nul

if errorlevel 1 (
  echo.
  echo FAILED. Is Factorio still running?
) else (
  echo.
  echo Deployed to %DEST%
  echo Start Factorio - it will be in the mod list, on by default.
)
echo.
pause
