@echo off
setlocal
title Aincrad - Full Floor One Pregeneration
cd /d "%~dp0"

echo.
echo  ========================================================
echo   FULL FLOOR ONE PREGENERATION
echo  ========================================================
echo.
echo   This generates terrain for all 308,028 chunks of
echo   Floor One. Measured rate is roughly 30-40 chunks per
echo   second, so expect about 2.5 to 3 hours.
echo.
echo   It is SAFE to close this window at any time.
echo   Progress is saved as a cursor in the generation ledger,
echo   so re-running this script resumes where it stopped
echo   rather than starting over. Already-finished chunks -
echo   including the whole Town review slice - are skipped.
echo.
echo   Disk: expect the server folder to grow to roughly 4 GB.
echo.
echo   Leave it running overnight and check
echo   "aincrad pregen status" afterwards.
echo.
pause

powershell -NoProfile -File "%~dp0scripts\run-pregen.ps1"

echo.
echo  Pregeneration session ended. Run this script again to resume
echo  if the cursor has not reached 308028.
echo.
pause
endlocal
