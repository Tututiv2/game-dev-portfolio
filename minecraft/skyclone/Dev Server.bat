@echo off
setlocal
title SkyClone - Dev Server (localhost:25630)
cd /d "%~dp0"

echo.
echo  ============================================
echo    SKYCLONE - DEV SERVER
echo    Connect to:  localhost:25630
echo    RCON:        25631  (scripts\rcon.ps1 "sb info")
echo.
echo    Builds the plugin, deploys it, starts Paper.
echo    Keep this window open while you play.
echo    Type  stop  here to shut down cleanly.
echo  ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-server.ps1"
set EXIT_CODE=%ERRORLEVEL%

echo.
echo  ============================================
if %EXIT_CODE% NEQ 0 (
    echo    SERVER STOPPED WITH AN ERROR ^(code %EXIT_CODE%^)
    echo.
    echo    Scroll UP to read the error above.
    echo    Build errors are printed before "DEPLOY FAILED".
    echo    Server errors are in server\logs\latest.log
) else (
    echo    Server stopped normally.
)
echo  ============================================
echo.
echo  This window stays open on purpose so you can read what happened.
pause
endlocal
