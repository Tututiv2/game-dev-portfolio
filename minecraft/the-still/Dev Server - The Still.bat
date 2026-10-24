@echo off
title The Still - dev server (localhost:25580)
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-server.ps1"
set EXIT_CODE=%ERRORLEVEL%

echo.
if %EXIT_CODE% NEQ 0 (
    echo  Server exited with an error. See messages above.
) else (
    echo  Server stopped.
)
echo.
pause >nul
exit /b %EXIT_CODE%
