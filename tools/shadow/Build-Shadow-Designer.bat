@echo off
setlocal
set "APP_DIR=%~dp0SHADOW_DESIGNER"

if not exist "%APP_DIR%\package.json" (
  echo SHADOW_DESIGNER project not found.
  pause
  exit /b 1
)

pushd "%APP_DIR%"
call npm run build:exe
if errorlevel 1 (
  echo Build failed.
  popd
  pause
  exit /b 1
)
popd

if exist "%~dp0SHADOW_DESIGNER\release\Shadow-Designer.exe" (
  start "" "%~dp0SHADOW_DESIGNER\release\Shadow-Designer.exe"
)
