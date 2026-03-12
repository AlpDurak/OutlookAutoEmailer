@echo off
echo Building OutlookAutoEmailer Windows installer...
mvn clean package -Ppackage-exe -DskipTests
echo.
if %ERRORLEVEL% == 0 (
    echo SUCCESS! Installer is in: target\installer\
) else (
    echo BUILD FAILED. Check the output above.
)
pause
