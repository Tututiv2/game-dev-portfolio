@echo off
REM Double-click this. Launches the GUI detached with no console window.
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0SessionSaver.ps1"
