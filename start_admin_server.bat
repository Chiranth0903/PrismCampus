@echo off
title PrismCampus Campus Studio
cd /d "%~dp0"
echo Open http://localhost:8000. See server\ONBOARDING.md for setup.
where python >nul 2>nul
if not errorlevel 1 (
 python server\uno_q_server.py
) else if exist "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" (
 "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" server\uno_q_server.py
) else (
 echo Install Python 3.12 or newer from python.org and retry.
)
pause
