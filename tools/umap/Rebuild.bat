@echo off
cd /d "%~dp0"
echo.
echo   Rescanning the portfolio and re-fitting the UMAP.
echo.
py -3 tools\rebuild.py
echo.
pause
