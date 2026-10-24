@echo off
title AuraQoL - tests
cd /d "%~dp0"
echo   Unit tests (no network) ...
call gradlew.bat test --console=plain
echo.
echo   Live price-source checks (needs internet) ...
call gradlew.bat test -Plive=true --console=plain
echo.
echo   Report: build\reports\tests\test\index.html
pause
