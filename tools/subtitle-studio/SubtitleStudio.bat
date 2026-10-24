@echo off
cd /d "%~dp0"

if not exist ".venv\Scripts\pythonw.exe" (
  echo.
  echo  Not set up yet - double-click INSTALL.bat first.
  echo.
  pause
  exit /b 1
)

set "HF_HUB_DISABLE_SYMLINKS_WARNING=1"

REM pythonw = no console window behind the app.
REM %* forwards a video if one was dragged onto this file.
start "" ".venv\Scripts\pythonw.exe" app.py %*
