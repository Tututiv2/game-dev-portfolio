@echo off
title NEON NOMAD 3D
cd /d "%~dp0"
node tools\serve.js
if errorlevel 1 pause
