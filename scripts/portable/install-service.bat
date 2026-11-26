@echo off
setlocal
rem Устанавливает k6-lab как службу автозапуска Windows (задача планировщика от имени SYSTEM, без лимита 72ч, перезапуск при сбое).
rem Запускать от администратора. Сначала один раз запустите start.bat и пройдите мастер настройки PostgreSQL.
set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
cd /d "%DIR%"
net session >nul 2>&1 || (echo Запустите от имени администратора & pause & exit /b 1)
if not exist "config\k6lab.properties" (echo Сначала запустите start.bat и пройдите мастер настройки, затем повторите. & pause & exit /b 1)
set "JAVA=java"
if exist "%DIR%\jre\bin\java.exe" set "JAVA=%DIR%\jre\bin\java.exe"

rem Отдельный обёрточный скрипт избегает проблем с экранированием пробелов и лимитом длины /TR
(
  echo @echo off
  echo cd /d "%DIR%"
  echo "%JAVA%" -Xmx1g -jar "%DIR%\k6-lab-admin.jar" ^>^> "%DIR%\k6-lab.log" 2^>^&1
) > "%DIR%\run-service.cmd"

set "XML=%TEMP%\k6-lab-task-%RANDOM%.xml"
(
  echo ^<?xml version="1.0" encoding="UTF-16"?^>
  echo ^<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task"^>
  echo   ^<Triggers^>^<BootTrigger^>^<Enabled^>true^</Enabled^>^</BootTrigger^>^</Triggers^>
  echo   ^<Principals^>^<Principal id="Author"^>^<UserId^>S-1-5-18^</UserId^>^<RunLevel^>HighestAvailable^</RunLevel^>^</Principal^>^</Principals^>
  echo   ^<Settings^>
  echo     ^<MultipleInstancesPolicy^>IgnoreNew^</MultipleInstancesPolicy^>
  echo     ^<DisallowStartIfOnBatteries^>false^</DisallowStartIfOnBatteries^>
  echo     ^<StopIfGoingOnBatteries^>false^</StopIfGoingOnBatteries^>
  echo     ^<ExecutionTimeLimit^>PT0S^</ExecutionTimeLimit^>
  echo     ^<RestartOnFailure^>^<Interval^>PT1M^</Interval^>^<Count^>999^</Count^>^</RestartOnFailure^>
  echo     ^<Enabled^>true^</Enabled^>
  echo   ^</Settings^>
  echo   ^<Actions Context="Author"^>^<Exec^>^<Command^>%DIR%\run-service.cmd^</Command^>^<WorkingDirectory^>%DIR%^</WorkingDirectory^>^</Exec^>^</Actions^>
  echo ^</Task^>
) > "%XML%"
schtasks /Create /F /TN "k6-lab" /XML "%XML%" || (del /f /q "%XML%" >nul 2>&1 & exit /b 1)
del /f /q "%XML%" >nul 2>&1
schtasks /Run /TN "k6-lab"
echo Служба k6-lab установлена и запущена (без лимита 72ч, с автоперезапуском). Лог: %DIR%\k6-lab.log. Удаление: uninstall-service.bat
pause
