@echo off
title Aura Client - dev client
cd /d "%~dp0"
echo.
echo   Launching Aura Client (dev Minecraft with AuraCapes loaded).
echo   Accounts: top-right of the main menu.  Cosmetics: G, or the menu.
echo.
rem Copy any servers from your normal Minecraft / MyFire Launcher into the
rem Multiplayer list. Never removes anything; a failure never blocks launch.
py -3 tools\sync_servers.py 2>nul
echo.
call gradlew.bat runClient
pause
