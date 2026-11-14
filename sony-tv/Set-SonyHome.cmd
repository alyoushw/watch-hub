@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Set-SonyHome.ps1"
if errorlevel 1 echo Setup stopped. Read the message above. No apps were removed or disabled.
pause
