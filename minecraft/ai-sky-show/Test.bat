@echo off
rem Headless tests for the whole show rig (no server, no client, no OBS, no model calls).
title AISkyShow - tests
cd /d "%~dp0"
set "PYTHONPATH=%~dp0orchestrator;%PYTHONPATH%"
py -3 -m unittest discover -s tests %*
set RC=%ERRORLEVEL%
echo.
if %RC%==0 (echo ALL TESTS PASSED) else (echo TESTS FAILED - exit %RC%)
if "%~1"=="" pause
exit /b %RC%
