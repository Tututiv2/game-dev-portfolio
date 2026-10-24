@echo off
REM THRESHOLD - the verification suite.
REM   1. the pure-logic checks (solver, acoustic model, continuity) run in node
REM   2. the spectral checks need real Web Audio, so they run in a browser

cd /d "%~dp0"

echo.
echo   ---- pure logic (node) ----------------------------------------
node tests/run-node.mjs
echo.
echo   ---- rendered audio (headless Chrome) -------------------------
node tools/headless.mjs
echo.
echo   If headless Chrome is unavailable, run Play.bat and open
echo   http://localhost:5520/?test in your browser instead.
echo.
pause
