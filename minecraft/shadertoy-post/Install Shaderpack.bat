@echo off
setlocal
cd /d "%~dp0"

set "DEST=%APPDATA%\.minecraft\shaderpacks"

echo.
echo   Installing "Shadertoy Post" ...
echo.

if not exist "%APPDATA%\.minecraft" (
  echo   Could not find %APPDATA%\.minecraft
  echo.
  echo   If you launch Minecraft through Prism, MultiMC, CurseForge or a
  echo   custom instance folder, its shaderpacks folder is somewhere else.
  echo   Copy the "ShadertoyPost" folder into that instance's shaderpacks
  echo   folder by hand instead.
  echo.
  pause
  exit /b 1
)

if not exist "%DEST%" mkdir "%DEST%"

robocopy "ShadertoyPost" "%DEST%\ShadertoyPost" /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
  echo   Copy FAILED. Is Minecraft running with the pack loaded?
  echo.
  pause
  exit /b 1
)

echo   Installed to:
echo     %DEST%\ShadertoyPost
echo.
echo   In game:  Options - Video Settings - Shader Packs - Shadertoy Post
echo   Then:     Shader Pack Settings - Effect
echo.
echo   Re-run this after editing custom.glsl to push the change across.
echo.
pause
