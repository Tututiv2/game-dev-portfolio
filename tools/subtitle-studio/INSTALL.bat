@echo off
setlocal enabledelayedexpansion
title SubtitleStudio - Install
cd /d "%~dp0"

echo.
echo  ================================================================
echo    SubtitleStudio - one-time setup
echo  ================================================================
echo.
echo  This creates a private Python environment inside this folder and
echo  downloads what the app needs. It does not touch anything else on
echo  your PC. Takes a few minutes on the first run.
echo.

REM ---- 1. Find Python 3.10 -------------------------------------------------
REM 3.13 is deliberately avoided: several of these wheels have no 3.13 build.
set "PY="
for %%P in (
  "%LOCALAPPDATA%\Programs\Python\Python310\python.exe"
  "%ProgramFiles%\Python310\python.exe"
  "C:\Python310\python.exe"
) do if exist %%P set "PY=%%~P"

if not defined PY (
  py -3.10 -c "import sys" >nul 2>&1
  if not errorlevel 1 for /f "delims=" %%I in ('py -3.10 -c "import sys;print(sys.executable)"') do set "PY=%%I"
)

if not defined PY (
  echo  [X] Could not find Python 3.10.
  echo.
  echo      Install it from https://www.python.org/downloads/release/python-31011/
  echo      Choose the "Windows installer (64-bit)" and tick "Add to PATH".
  echo.
  pause
  exit /b 1
)
echo  [1/5] Python found: !PY!

REM ---- 2. ffmpeg -----------------------------------------------------------
set "FF="
where ffmpeg >nul 2>&1 && set "FF=ffmpeg"
if not defined FF if exist "%USERPROFILE%\Documents\FFmpeg\bin\ffmpeg.exe" set "FF=%USERPROFILE%\Documents\FFmpeg\bin\ffmpeg.exe"
if not defined FF (
  echo  [X] ffmpeg was not found on PATH.
  echo      Install it from https://www.gyan.dev/ffmpeg/builds/ and add its
  echo      bin folder to PATH, then run this installer again.
  pause
  exit /b 1
)
echo  [2/5] ffmpeg found.

REM ---- 3. Virtual environment ---------------------------------------------
if exist ".venv\Scripts\python.exe" (
  echo  [3/5] Reusing existing environment.
) else (
  echo  [3/5] Creating environment...
  "!PY!" -m venv .venv
  if errorlevel 1 ( echo  [X] Could not create the environment. & pause & exit /b 1 )
)

REM ---- 4. Dependencies -----------------------------------------------------
echo  [4/5] Installing dependencies. This is the slow part - about 2 GB.
echo.
".venv\Scripts\python.exe" -m pip install --upgrade pip --quiet --disable-pip-version-check
".venv\Scripts\python.exe" -m pip install -r requirements.txt --disable-pip-version-check
if errorlevel 1 ( echo. & echo  [X] Install failed. Scroll up for the reason. & pause & exit /b 1 )

REM ---- 5. Verify the GPU stack actually loads ------------------------------
echo.
echo  [5/5] Checking the GPU stack...
".venv\Scripts\python.exe" -c "import sys; sys.path.insert(0,'src'); from core.transcribe import cuda_available, cuda_runtime_ok; ok,missing = cuda_runtime_ok(); print('       GPU: ' + ('ready (NVIDIA, float16)' if (cuda_available() and ok) else 'NOT available - will run on CPU, about 20x slower')); print('       missing DLLs: ' + ', '.join(missing)) if missing else None"

echo.
echo  ================================================================
echo    Done.
echo.
echo    Double-click  SubtitleStudio.bat  to open the app.
echo    (Or drag a video onto  Subtitle.bat  to do it without the UI.)
echo  ================================================================
echo.
pause
