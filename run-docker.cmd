@echo off
cd /d "%~dp0"

echo ==========================================
echo ID995A AI Form Review - Docker Mode
echo ==========================================
echo.

REM Check if Docker is running
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop first.
    pause
    exit /b 1
)

echo Building and starting services...
echo   - RAG Service    : http://localhost:8090
echo   - OCR Service    : http://localhost:8091
echo   - Backend (Java) : http://localhost:8080/api/health
echo   - Frontend (Vue) : http://localhost:5173
echo   - PP-OCR det/rec: http://localhost:8001 / http://localhost:8002
echo.

docker-compose up --build -d

if errorlevel 1 (
    echo.
    echo [ERROR] Docker compose failed.
    pause
    exit /b 1
)

echo.
echo Services are starting...
timeout /t 5 /nobreak >nul

echo.
echo Checking service health...
docker-compose ps

echo.
echo ==========================================
echo All services started successfully!
echo ==========================================
echo Frontend : http://localhost:5173
echo Backend  : http://localhost:8080/api/health
echo RAG      : http://localhost:8090/health
echo OCR      : http://localhost:8091/health
echo.
echo To stop  : run-docker-stop.cmd
echo To logs  : docker-compose logs -f
echo ==========================================
pause
