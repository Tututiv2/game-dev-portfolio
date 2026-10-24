@echo off
title AuraCapes - build
cd /d "%~dp0"
echo   Building for 26.2 ...
call gradlew.bat build -q
echo   Building for 26.1.2 ...
call gradlew.bat build -q -Pminecraft_version=26.1.2 -Pfabric_api_version=0.155.3+26.1.2 -Ploader_version=0.19.5
echo.
echo   Jars are in build\libs\  (auracapes-1.0.0+mc26.2.jar, auracapes-1.0.0+mc26.1.2.jar)
echo   Each goes in the mods folder of the matching MinecraftLauncher instance.
echo.
pause
