@echo off
REM Run the full verification suite: unit tests, an in-world combat test, and a
REM load against every mod in the real mods folder. Factorio must be closed.
powershell -ExecutionPolicy Bypass -File "%~dp0tools\test.ps1" -Level all
pause
