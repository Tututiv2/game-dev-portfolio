@echo off
title Second Watch - friend pack
cd /d "%~dp0"

echo.
echo   Building the mod and collecting everything your friend needs.
echo.

call gradlew.bat build --console=plain
if errorlevel 1 goto failed

if not exist "FriendPack" mkdir "FriendPack"

copy /y "build\libs\secondwatch-0.1.0.jar" "FriendPack\" >nul
copy /y "run\mods\voicechat-fabric-2.6.22+26.2.jar" "FriendPack\" >nul

echo Second Watch - what to install> "FriendPack\READ ME.txt"
echo.>> "FriendPack\READ ME.txt"
echo 1. Install Fabric Loader for Minecraft 26.2 (fabricmc.net/use/installer)>> "FriendPack\READ ME.txt"
echo 2. Put EVERY .jar in this folder into your .minecraft\mods folder>> "FriendPack\READ ME.txt"
echo 3. You also need Fabric API 0.157.0+26.2 - get it from Modrinth>> "FriendPack\READ ME.txt"
echo 4. Launch the "fabric-loader-26.2" profile from the Minecraft launcher>> "FriendPack\READ ME.txt"
echo 5. Multiplayer -^> Direct Connection -^> the address you were given>> "FriendPack\READ ME.txt"
echo.>> "FriendPack\READ ME.txt"
echo Both of you must run the SAME version of every mod, or the phantom>> "FriendPack\READ ME.txt"
echo is interpreted differently on each end and the horror becomes a crash.>> "FriendPack\READ ME.txt"

echo.
echo   Done. Send them the FriendPack folder.
echo.
echo   Still missing from it: Fabric API 0.157.0+26.2, which cannot be
echo   redistributed from here - grab it from Modrinth and drop it in.
echo.
start "" "FriendPack"
pause
exit /b 0

:failed
echo.
echo   BUILD FAILED - nothing was packed.
pause
