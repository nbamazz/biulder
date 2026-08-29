@echo off
where mvn >nul 2>nul
if %errorlevel% neq 0 (
    echo Maven isn't installed. Get it from https://maven.apache.org/download.cgi
    echo Or push this folder to GitHub and use the included Actions workflow instead.
    exit /b 1
)
call mvn -q clean package
echo.
echo Build complete! Jar is at: target\PrimeSpawn.jar
