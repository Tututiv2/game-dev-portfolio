@echo off
cd /d "%~dp0plugin"
call gradlew.bat test --console=plain
pause
