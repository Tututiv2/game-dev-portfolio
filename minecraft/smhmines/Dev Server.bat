@echo off
setlocal
title SmhMines - Dev Server (localhost:25600)
cd /d "%~dp0"

echo.
echo  ============================================
echo    SMHMINES - DEV SERVER
echo    Connect to:  localhost:25600
echo    (25565-25579 belong to the prison server)
echo.
echo    Keep this window open while you work.
echo  ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-server-managed.ps1"
set EXIT_CODE=%ERRORLEVEL%

echo.
echo  ============================================
if %EXIT_CODE% NEQ 0 (
    echo    SERVER STOPPED WITH AN ERROR ^(code %EXIT_CODE%^)
    echo.
    echo    Scroll UP to read the error above.
    echo    If it mentions datapacks, run "Reset World.bat".
) else (
    echo    Server stopped normally.
)
echo  ============================================
echo.
echo  This window stays open on purpose so you can read what happened.
pause
endlocal
