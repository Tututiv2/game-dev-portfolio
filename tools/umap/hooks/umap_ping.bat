@echo off
rem  Claude Code hook -> the UMAP server, the cheap way.
rem
rem  The Python version of this does the same job in 175 ms, and essentially
rem  all of that is interpreter startup -- paid on EVERY tool call of EVERY
rem  session in this folder. curl.exe ships with Windows and does it in a
rem  fraction of that, reading the hook payload straight off stdin.
rem
rem  `exit /b 0` is load-bearing: curl exits 7 when nothing is listening, and a
rem  PreToolUse hook that exits non-zero decorates the transcript with an error
rem  on every single tool call. A map that is not running must be silent.
rem  port.txt is written by install.py from instance.json: each copy of the map
rem  has its own port, and this .bat is the copy's own.
if "%UMAP_PORT%"=="" if exist "%~dp0port.txt" set /p UMAP_PORT=<"%~dp0port.txt"
if "%UMAP_PORT%"=="" set UMAP_PORT=5580
curl -s -m 1 -X POST -H "Content-Type: application/json" --data-binary @- "http://127.0.0.1:%UMAP_PORT%/api/event" >nul 2>&1
exit /b 0
