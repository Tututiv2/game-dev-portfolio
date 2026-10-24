@echo off
title Second Watch - launcher
cd /d "%~dp0"

echo.
echo   SECOND WATCH
echo   ------------
echo   Starting the server in its own window, then this window becomes the game.
echo.
echo   When Minecraft opens:  Multiplayer  -^>  Direct Connection  -^>  localhost
echo.

start "Second Watch - SERVER" cmd /k "gradlew.bat runServer --console=plain"

echo   Waiting for the server to come up...
timeout /t 30 /nobreak >nul

echo   Launching the game.
echo.
gradlew.bat runClient --console=plain

echo.
echo   Game closed. The server is still running in the other window -
echo   type  stop  in it to shut the world down cleanly.
pause
