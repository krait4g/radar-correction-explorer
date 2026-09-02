@echo off
setlocal
title Radar Correction Explorer

set "ROOT_DIR=%~dp0"
set "LAUNCHER=%ROOT_DIR%launcher\start-viewer.ps1"

if not exist "%LAUNCHER%" (
  echo [ERROR] Launcher not found: "%LAUNCHER%"
  echo.
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%LAUNCHER%"
set "VIEWER_EXIT=%ERRORLEVEL%"

if "%VIEWER_EXIT%"=="-1073741510" exit /b 0
if "%VIEWER_EXIT%"=="130" exit /b 0

if not "%VIEWER_EXIT%"=="0" (
  echo.
  echo [ERROR] Radar Correction Explorer stopped with exit code %VIEWER_EXIT%.
  echo Review the message above, then press any key to close this window.
  pause >nul
)

exit /b %VIEWER_EXIT%
