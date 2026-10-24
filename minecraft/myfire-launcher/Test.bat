@echo off
setlocal
title Lovely - tests

set "HERE=%~dp0"

echo.
echo   Running the offline test suite (no network, no game, no account).
echo   Add the word  net  after the .bat to include the network tier.
echo.

where py >nul 2>&1
if %errorlevel%==0 (
    if /I "%~1"=="net" ( py -3 "%HERE%tests\run_tests.py" --net ) else ( py -3 "%HERE%tests\run_tests.py" )
) else (
    if /I "%~1"=="net" ( python "%HERE%tests\run_tests.py" --net ) else ( python "%HERE%tests\run_tests.py" )
)

echo.
pause
