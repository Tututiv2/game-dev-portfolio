@echo off
title GLEANER
cd /d "%~dp0"
set PORT=5490

echo.
echo   GLEANER  -  http://localhost:%PORT%/
echo   Close this window to stop the server.
echo.

REM node is tried FIRST on purpose: serve.js sends Cache-Control: no-cache.
REM Python's http.server does not, so an edited module keeps serving stale and
REM you debug a file the browser never loaded. That cost real time once already.
where node >nul 2>nul
if %errorlevel%==0 (
    start "" "http://localhost:%PORT%/"
    node serve.js %PORT%
    exit /b
)

where python >nul 2>nul
if %errorlevel%==0 (
    echo   NOTE: using python - hard-refresh ^(Ctrl+F5^) after editing src/
    start "" "http://localhost:%PORT%/"
    python -m http.server %PORT% --bind 127.0.0.1
    exit /b
)

where py >nul 2>nul
if %errorlevel%==0 (
    start "" "http://localhost:%PORT%/"
    py -m http.server %PORT% --bind 127.0.0.1
    exit /b
)

echo   Could not find python or node on PATH.
echo   The game needs a local server - ES modules will not load from file://
pause
