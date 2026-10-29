@echo off
rem Double-click to open the ManetNode launcher (keep ManetNode.ps1 in the same folder).
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0ManetNode.ps1"
