@echo off
rem Удаляет задачу автозапуска k6-lab (данные и настройки не трогает). Запускать от администратора.
net session >nul 2>&1 || (echo Запустите от имени администратора & pause & exit /b 1)
schtasks /End /TN "k6-lab" >nul 2>&1
schtasks /Delete /F /TN "k6-lab"
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*k6-lab-admin.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1
echo Служба k6-lab удалена.
pause
