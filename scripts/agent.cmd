@echo off
start "ngo-platform agent" powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0agent.ps1"
