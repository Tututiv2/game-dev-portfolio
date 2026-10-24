@echo off
cd /d "%~dp0"
if not exist "web\data\graph.json" (
  echo   No map yet - building it first. This takes about a minute.
  py -3 tools\rebuild.py
)
start "" py -3 server.py --open
