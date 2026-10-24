@echo off
rem Can 4 AIs Beat Hypixel SkyBlock? - double-click to run one episode.
rem Preflight first (refuses if Rocket League is open), then the episode records into episodes\.
rem Extra args pass through, e.g.:  "Start Episode.bat" --minutes 10
title AISkyShow - episode
cd /d "%~dp0orchestrator"
py -3 run.py preflight
if errorlevel 1 (
  echo.
  echo Preflight failed - fix the FAIL lines above, then double-click again.
  pause
  exit /b 1
)
py -3 run.py episode --skip-preflight %*
echo.
echo Episode finished. Outputs are in the newest folder under episodes\
pause
