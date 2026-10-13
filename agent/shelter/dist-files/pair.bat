@echo off
chcp 65001 >nul
rem HotelPulse agentini ulash: ekranda kod chiqadi, HotelPulse -> Boshqaruv -> Agentlar sahifasida tasdiqlanadi.
cd /d "%~dp0"
"%~dp0jre\bin\java.exe" -jar "%~dp0shelter-agent.jar" pair --hotel "Aydarko'l Resort"
echo.
pause
