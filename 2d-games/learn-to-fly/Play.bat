@echo off
title LEARN TO FLY
cd /d "%~dp0"
set PORT=5500

echo.
echo   LEARN TO FLY  -  http://localhost:%PORT%/
echo   Close this window to stop the server.
echo.

REM node is tried FIRST on purpose: serve.mjs sends Cache-Control: no-cache. Python's
REM http.server does not, so an edited module keeps serving stale and you spend an hour
REM debugging a file the browser never loaded.
where node >nul 2>nul
if %errorlevel%==0 (
    start "" "http://localhost:%PORT%/"
    node tools\serve.mjs %PORT%
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
    echo   NOTE: using python - hard-refresh ^(Ctrl+F5^) after editing src/
    start "" "http://localhost:%PORT%/"
    py -m http.server %PORT% --bind 127.0.0.1
    exit /b
)

echo   Could not find node or python on PATH.
echo   The game needs a local server - ES modules will not load from file://
pause
