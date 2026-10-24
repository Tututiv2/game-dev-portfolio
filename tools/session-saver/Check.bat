@echo off
REM Shows what the app can see and the exact command a reopen would run.
REM Closes nothing, launches nothing. Run this first if something looks wrong.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0SessionSaver.ps1" -SelfTest
echo.
pause
