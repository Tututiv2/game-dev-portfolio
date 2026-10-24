@echo off
cd /d "%~dp0"
start "SkyClone Progress" /min py -3 tools\progress\serve.py
timeout /t 2 >nul
start "" http://localhost:5660/
