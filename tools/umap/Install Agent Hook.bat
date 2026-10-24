@echo off
cd /d "%~dp0"
py -3 hooks\install.py %*
echo.
pause
