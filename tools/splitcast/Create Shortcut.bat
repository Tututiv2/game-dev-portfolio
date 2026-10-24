@echo off
setlocal
cd /d "%~dp0"
title SplitCast - create shortcut
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\make_shortcut.ps1"
echo.
echo Done. SplitCast is on your Desktop and in the Start menu.
echo Launching it from there opens NO console window.
echo.
pause
endlocal
