@echo off
setlocal
title SubtitleStudio
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
  echo.
  echo  Not set up yet - double-click INSTALL.bat first.
  echo.
  pause
  exit /b 1
)

if "%~1"=="" (
  echo.
  echo  ================================================================
  echo    SubtitleStudio
  echo  ================================================================
  echo.
  echo   Drag a video file onto this .bat to subtitle it.
  echo.
  echo   Available styles:
  echo.
  ".venv\Scripts\python.exe" subtitle.py --list-themes
  echo.
  pause
  exit /b 0
)

set "HF_HUB_DISABLE_SYMLINKS_WARNING=1"

REM %* passes every dragged file plus any flags you add to a shortcut.
:next
".venv\Scripts\python.exe" subtitle.py %1 %SUBTITLE_FLAGS%
shift
if not "%~1"=="" goto next

echo.
pause
