@echo off
rem Rebuilds every flora mesh (Blender, headless) into game\assets\flora\ and
rem rewrites game\assets\flora\manifest.json. About 5 seconds per tree variant.
cd /d "%~dp0"
echo Building VESPER flora with Blender 5.1 ...
"C:\Program Files\Blender Foundation\Blender 5.1\blender.exe" --background --factory-startup --python "_tools\flora\build_flora.py" -- %*
echo.
echo Done. Double-click FLORA.bat to look at them.
pause
