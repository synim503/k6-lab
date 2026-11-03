@echo off
rem k6-lab: запуск портативной версии (Windows). Нужна Java 17+ (папка jre\ рядом имеет приоритет).
cd /d "%~dp0"
set JAVA=java
if exist "jre\bin\java.exe" set JAVA=jre\bin\java.exe
"%JAVA%" -Xmx1g -jar k6-lab-admin.jar
pause
