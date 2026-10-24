@echo off
title AuraQoL - build
cd /d "%~dp0"
echo   Building for 26.2 (runs the unit tests too) ...
call gradlew.bat build -q
if errorlevel 1 goto failed
echo   Building for 26.1.2 ...
call gradlew.bat build -q -Pminecraft_version=26.1.2 -Pfabric_api_version=0.155.3+26.1.2 -Ploader_version=0.19.5
if errorlevel 1 goto failed
echo.
echo   Jars are in build\libs\  (auraqol-1.0.0+mc26.2.jar, auraqol-1.0.0+mc26.1.2.jar)
echo   Each goes in the mods folder of the matching Minecraft install.
echo.
pause
exit /b 0
:failed
echo.
echo   BUILD FAILED - scroll up for the error.
echo.
pause
exit /b 1
