@echo off
chcp 65001 >nul
rem HotelPulse - UCS Shelter bazasi tuzilishini aniqlash (FAQAT O'QIYDI) va HotelPulse'ga yuklash.
set HOST=172.10.0.2
set DB=D:\Shelter\IBDATA\SHELTER.FDB
set FBUSER=SYSDBA

cd /d "%~dp0"
"%~dp0jre\bin\java.exe" -jar "%~dp0shelter-agent.jar" discover --host %HOST% --db "%DB%" --user %FBUSER%
echo.
pause
