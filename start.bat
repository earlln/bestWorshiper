@echo off
cd /d "%~dp0"
set PORT=8090
set URL=http://localhost:%PORT%/bestWorshiper.html

start "bestWorshiper-Server" cmd /k "node serve.js %PORT%"
timeout /t 2 >nul
start "" "%URL%"
