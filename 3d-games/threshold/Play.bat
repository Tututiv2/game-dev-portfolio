@echo off
REM THRESHOLD - double-click to play.
REM ES modules will not load from file:// (the browser blocks module imports on that scheme),
REM so this serves the folder over http on 5520 and opens it.

cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo   Node.js was not found on PATH. It is only used to serve these files.
  echo   Install from https://nodejs.org and run this again.
  echo.
  pause
  exit /b 1
)

echo.
echo   THRESHOLD  -  http://localhost:5520
echo   Headphones strongly recommended.
echo   Close this window to stop the server.
echo.

start "" http://localhost:5520/
node tools/serve.mjs 5520
