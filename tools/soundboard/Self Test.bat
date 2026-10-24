@echo off
rem  Proves the engine works: decodes every sound in Sounds\, exercises the mixer,
rem  and records the PC's own output back to confirm sound really came out.
rem  IT WILL MAKE NOISE. That is the point of the last check.
cd /d "%~dp0"
dotnet build "tests\SelfTest.csproj" -c Release -v quiet --nologo
if errorlevel 1 ( echo Build failed. & pause & exit /b 1 )
"tests\bin\SelfTest.exe"
pause
