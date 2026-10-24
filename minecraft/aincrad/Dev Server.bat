@echo off
setlocal
title SAO Prison - Dev Server
cd /d "%~dp0"

echo.
echo  ========================================================
echo   SWORD ART ONLINE PRISON - DEVELOPMENT SERVER
echo   Port: 25577
echo  ========================================================
echo.
echo   This launcher will NOT create the world until the
echo   custom-height first-boot checklist has been completed.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-dev-server.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo  Dev server was not started. Read the message above.
) else (
    echo  Dev server stopped normally.
)
echo.
pause
exit /b %EXIT_CODE%
