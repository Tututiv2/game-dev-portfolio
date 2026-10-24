@echo off
setlocal
cd /d "%~dp0"
title SplitCast - checks

echo ================================================
echo  SplitCast checks
echo ================================================
echo.
py -3 tests\test_overlay.py
echo.
py -3 tests\test_gui_build.py
echo.
py -3 tests\run_tests.py %*
echo.
pause
endlocal
