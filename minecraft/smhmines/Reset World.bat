@echo off
setlocal
title SmhMines - Reset World
cd /d "%~dp0"

echo.
echo  ============================================
echo    RESET WORLD
echo.
echo    Stops the server and MOVES the current
echo    world to world-old-^<timestamp^>.
echo    Nothing is deleted - you can always move
echo    it back.
echo.
echo    Needed after changing world height.
echo  ============================================
echo.
set /p CONFIRM=Type Y and press Enter to continue:
if /I not "%CONFIRM%"=="Y" (
    echo.
    echo  Cancelled. Nothing changed.
    pause
    exit /b 0
)

echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\reset-world.ps1"
set EXIT_CODE=%ERRORLEVEL%

echo.
if %EXIT_CODE% NEQ 0 (
    echo  RESET FAILED ^(code %EXIT_CODE%^) - read the error above.
    echo  Most common cause: the Dev Server window is still open. Close it first.
) else (
    echo  Done. Now launch "Dev Server.bat".
)
echo.
pause
endlocal
