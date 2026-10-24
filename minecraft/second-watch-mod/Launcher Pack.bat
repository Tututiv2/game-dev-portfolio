@echo off
title Second Watch - launcher pack
cd /d "%~dp0"

echo.
echo   Builds the mod, then packs MyHorrorMod-26.2.mrpack - one file that any
echo   modern launcher imports as a whole isolated instance:
echo.
echo     Lovely Launcher  ->  Import pack
echo     Prism / Modrinth app  ->  Add instance -^> from file
echo.
echo   Use this instead of FriendPack when the other end has a launcher that
echo   understands .mrpack, which is all of them except the vanilla one.
echo.

call gradlew.bat build --console=plain
if errorlevel 1 goto failed

py -3 tools\make_pack.py
if errorlevel 1 goto failed

echo.
echo   The pack points at localhost:25597 - the HORROR server in MyFireServer,
echo   started by "Start Horror Server.bat". Someone else importing it still
echo   has to add YOUR address; theirs will point at their own machine.
echo.
pause
exit /b 0

:failed
echo.
echo   FAILED - no pack was written.
pause
exit /b 1
