@echo off
setlocal
cd /d "%~dp0"

where python >nul 2>&1
if errorlevel 1 (
  echo.
  echo   Python not found on PATH — needed only to serve the files locally,
  echo   because browsers refuse to read them straight off disk.
  echo.
  pause
  exit /b 1
)

set "PORT=8731"

echo.
echo   Serving this folder on http://127.0.0.1:%PORT%
echo.

start "shadertoy-shader-server" /min cmd /c python -m http.server %PORT% --bind 127.0.0.1
timeout /t 2 /nobreak >nul

start "" "http://127.0.0.1:%PORT%/_verify/preview.html"
start "" "http://127.0.0.1:%PORT%/_verify/verify.html"

echo   Opened two tabs:
echo     preview.html  - every effect rendered live on a test scene
echo     verify.html   - compile + link check, with errors and line numbers
echo.
echo   Edit a .glsl file, then just refresh the tab.
echo.
echo   Press any key to stop the server and close.
pause >nul

taskkill /FI "WINDOWTITLE eq shadertoy-shader-server*" /F >nul 2>&1
