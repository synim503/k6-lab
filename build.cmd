@echo off
rem Сборка всего проекта одной командой на Windows: build.cmd [--with-tests] [--no-xk6] [--k6 v1.7.1] [--check]
rem Нужны JDK 17+, Go 1.22+ и Git for Windows (его bash). Результат: portable\k6-lab-portable.zip
setlocal
set "BASH="
for %%P in ("%ProgramFiles%\Git\bin\bash.exe" "%ProgramFiles(x86)%\Git\bin\bash.exe" "%LOCALAPPDATA%\Programs\Git\bin\bash.exe") do if exist %%P set "BASH=%%~P"
if not defined BASH for /f "delims=" %%B in ('where bash 2^>nul') do if not defined BASH set "BASH=%%B"
if not defined BASH (
  echo Не найден bash. Установите Git for Windows: https://git-scm.com/download/win
  exit /b 1
)
"%BASH%" "%~dp0build.sh" %*
exit /b %ERRORLEVEL%
