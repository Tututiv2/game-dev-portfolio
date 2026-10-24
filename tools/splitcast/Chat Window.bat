@echo off
setlocal
cd /d "%~dp0"
title SplitCast - chat window
py -3 -c "import sys; sys.path.insert(0,\".\"); from splitcast import chatwindow, config; ok,d = chatwindow.open_window(config.load()); print((\"opened via \"+d) if ok else (\"FAILED: \"+d))"
timeout /t 3 >nul
endlocal
