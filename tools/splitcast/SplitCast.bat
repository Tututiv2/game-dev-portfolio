@echo off
setlocal
cd /d "%~dp0"
title SplitCast

py -3 -m splitcast gui
if errorlevel 1 (
  echo.
  echo SplitCast exited with an error. The lines above are the reason.
  pause
)
endlocal
