@echo off
setlocal
cd /d "%~dp0"
title SplitCast - connect YouTube
echo Close SplitCast first if it is open.
echo.
py -3 tools\connect_youtube.py
echo.
pause
endlocal
