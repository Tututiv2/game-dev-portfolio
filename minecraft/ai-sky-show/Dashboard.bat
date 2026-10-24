@echo off
rem AISkyShow control room: dashboard http://127.0.0.1:5650/  +  OBS overlay http://127.0.0.1:5650/overlay
rem   Dashboard.bat        serve the dashboard + overlay (an episode attaches to it)
rem   Dashboard.bat demo   loop a scripted fake episode so you can SEE the overlay (no game, no GPU)
rem   Dashboard.bat voice  demo + speak the captions with Windows SAPI
cd /d "%~dp0orchestrator"
if /i "%1"=="demo" (
  py -3 -m show.server --demo --open
) else if /i "%1"=="voice" (
  py -3 -m show.server --demo --voice --open
) else (
  py -3 -m show.server --open
)
pause
