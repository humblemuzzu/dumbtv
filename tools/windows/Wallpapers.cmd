@echo off
rem Opens the dumbtv home wallpaper window (wallpapers.ps1) without a console.
start "" powershell -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0wallpapers.ps1"
