@echo off
setlocal
cd /d "%~dp0"
title SplitCast - audio devices
py -3 -m splitcast devices
echo.
py -3 -m splitcast dryrun
echo.
pause
endlocal
