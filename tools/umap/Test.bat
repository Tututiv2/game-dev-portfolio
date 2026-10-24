@echo off
cd /d "%~dp0"
rem The venv has numpy/scipy/umap-learn, so it can run the layout and map-quality
rem checks. Bare py -3 runs everything else and SKIPS those out loud rather than
rem passing checks it could not perform.
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" tests\run_tests.py --live %*
) else (
  echo   No .venv - running the checks that need no numpy.
  echo   For the rest:  py -3 -m venv .venv ^&^& .venv\Scripts\pip install numpy scipy umap-learn
  echo.
  py -3 tests\run_tests.py %*
)
echo.
pause
