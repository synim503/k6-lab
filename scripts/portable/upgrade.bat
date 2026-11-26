@echo off
setlocal
rem k6-lab: перенос настроек и данных из прежней установки в новую (при переустановке из нового архива).
rem config\ - подключение к PostgreSQL и ключ шифрования секретов; data\ - файлы версий скриптов и архивы запусков.
rem Использование: upgrade.bat C:\путь\к\старому\k6-lab
if "%~1"=="" (
  echo Использование: upgrade.bat C:\путь\к\старому\k6-lab
  exit /b 2
)
if not exist "%~1" (
  echo Каталог %~1 не найден
  exit /b 2
)
for %%I in ("%~1") do set "OLD=%%~fI"
if "%OLD:~-1%"=="\" set "OLD=%OLD:~0,-1%"
set "NEW=%~dp0"
if "%NEW:~-1%"=="\" set "NEW=%NEW:~0,-1%"
if /I "%OLD%"=="%NEW%" (
  echo Старая и новая установки - один каталог, переносить нечего
  exit /b 0
)
cd /d "%NEW%"
if exist "config\k6lab.properties" if exist "%OLD%\config" (
  set "BAK=config.bak.%RANDOM%"
  xcopy "config" "%BAK%\" /E /I /Y /Q >nul
  echo Внимание: существующий config\ сохранён в %BAK%\
)
for %%D in (config data) do (
  if exist "%OLD%\%%D" (
    xcopy "%OLD%\%%D" "%%D\" /E /I /Y /Q >nul
    if errorlevel 1 (
      echo Ошибка копирования %%D\
      exit /b 1
    )
    echo Перенесено: %%D\
  ) else (
    echo В %OLD% нет %%D\ - пропускаем
  )
)
echo Готово. Остановите старую админку и запустите start.bat в этом каталоге.
