@echo off
title Split voice from music
cd /d "%~dp0"
if "%~1"=="" (
  echo.
  echo   Drag a video or audio file onto this .bat
  echo.
  pause
  exit /b
)
echo.
echo   File: %~nx1
echo.
echo   Only part of it need fixing? Give a range. Blank does the whole thing.
echo   Format 0:00 or 0:10:00 or seconds.
echo.
set /p ST=  start (blank = beginning): 
set /p EN=  end   (blank = end):       
echo.
".venv\Scripts\python.exe" split_audio.py "%~1" "%ST%" "%EN%"
echo.
pause
