@echo off
echo Building OutlookAutoEmailer...
mvn clean package -Ppackage-exe -DskipTests
echo.
if %ERRORLEVEL% == 0 (
    echo SUCCESS!
    echo.
    echo The app folder is at:
    echo   target\installer\OutlookAutoEmailer\
    echo.
    echo To run: double-click target\installer\OutlookAutoEmailer\OutlookAutoEmailer.exe
    echo To share: zip the entire target\installer\OutlookAutoEmailer\ folder.
    echo No Java installation required on the target PC.
) else (
    echo BUILD FAILED. Check the output above.
)
pause
