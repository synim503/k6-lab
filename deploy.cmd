@echo off
rem Обновление админки на хосте одной командой: deploy.cmd user@host [--no-build] [--dir /opt/k6-lab] [--arch arm64]
setlocal
set "BASH="
for %%P in ("%ProgramFiles%\Git\bin\bash.exe" "%ProgramFiles(x86)%\Git\bin\bash.exe" "%LOCALAPPDATA%\Programs\Git\bin\bash.exe") do if exist %%P set "BASH=%%~P"
if not defined BASH for /f "delims=" %%B in ('where bash 2^>nul') do if not defined BASH set "BASH=%%B"
if not defined BASH (
  echo Не найден bash. Установите Git for Windows: https://git-scm.com/download/win
  exit /b 1
)
"%BASH%" "%~dp0deploy.sh" %*
exit /b %ERRORLEVEL%
