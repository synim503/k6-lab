@echo off
setlocal
rem Устанавливает k6-lab как службу автозапуска Windows (задача планировщика от имени SYSTEM, перезапуск при сбое).
rem Запускать от администратора. Сначала один раз запустите start.bat и пройдите мастер настройки PostgreSQL.
set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
cd /d "%DIR%"
net session >nul 2>&1 || (echo Запустите от имени администратора & pause & exit /b 1)
if not exist "config\k6lab.properties" (echo Сначала запустите start.bat и пройдите мастер настройки, затем повторите. & pause & exit /b 1)
set "JAVA=java"
if exist "%DIR%\jre\bin\java.exe" set "JAVA=%DIR%\jre\bin\java.exe"
schtasks /Create /F /TN "k6-lab" /SC ONSTART /RU SYSTEM /RL HIGHEST /TR "cmd /c \"cd /d \"%DIR%\" && \"%JAVA%\" -Xmx1g -jar k6-lab-admin.jar >> k6-lab.log 2>&1\"" || exit /b 1
schtasks /Change /TN "k6-lab" /ENABLE >nul
schtasks /Run /TN "k6-lab"
echo Служба k6-lab установлена и запущена. Лог: %DIR%\k6-lab.log. Удаление: uninstall-service.bat
pause
