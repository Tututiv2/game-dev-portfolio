@echo off
cd /d "%~dp0"
echo.
echo Space Engineers Lite — starting a tiny local web server.
echo Your browser needs http:// (not file://) so ES modules load correctly.
echo After the server starts, open: http://localhost:8080/startmenu.html
echo.
start "" "http://localhost:8080/startmenu.html"
python -m http.server 8080
