@echo off
rem Удаляет задачу автозапуска k6-lab (данные и настройки не трогает). Запускать от администратора.
net session >nul 2>&1 || (echo Запустите от имени администратора & pause & exit /b 1)
schtasks /End /TN "k6-lab" >nul 2>&1
schtasks /Delete /F /TN "k6-lab"
taskkill /F /FI "WINDOWTITLE eq k6-lab*" >nul 2>&1
echo Служба k6-lab удалена.
pause
