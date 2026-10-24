@echo off
rem  Rebuild SoundBoard and show everything the compiler says.
cd /d "%~dp0"
dotnet build "app\SoundBoard.csproj" -c Release --nologo
echo.
echo Output: %~dp0bin\SoundBoard.exe
pause
