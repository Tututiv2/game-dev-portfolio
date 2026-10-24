@echo off
rem Opens the flora bench LIVE: a field of the crystal world's trees at eye height.
rem Drag = look, WASD = walk (shift = fast), N = day/night, V = next view,
rem [ ] = other world, TAB = next variant, L = LOD, G = wind, P = screenshot, ESC = quit.
cd /d "%~dp0"
if not exist "game\assets\flora\manifest.json" call "BUILD FLORA.bat"
start "" "_tools\godot\Godot_v4.7.2-stable_win64.exe" --path game --audio-driver Dummy res://scenes/flora_bench.tscn -- --live --view=vista --arch=crystal
