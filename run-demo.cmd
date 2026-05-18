@echo off
cd /d "%~dp0"

echo ==========================================
echo   ID995A AI Form Review - Demo Launcher
echo ==========================================
echo.
echo  [1] Local Mode   - Start with local Java/Python/Node
echo  [2] Docker Mode  - Start with Docker containers
echo.
set /p choice="Select mode (1 or 2): "

if "%choice%"=="1" goto local
if "%choice%"=="2" goto docker

echo Invalid choice. Please enter 1 or 2.
pause
exit /b 1

:local
echo.
echo Starting LOCAL mode...
echo   - RAG Service    (Python) : http://127.0.0.1:8090
echo   - OCR Service    (Python) : http://127.0.0.1:8091
echo   - Backend (Java + OCR)   : http://127.0.0.1:8080
echo   - Frontend (Vue)         : http://127.0.0.1:5173
echo   - PP-OCR det/rec models  : http://192.168.20.250:8001 / http://192.168.20.250:8002
echo.

REM Check backend jar exists
if not exist "%~dp0backend\target\id995a-review-backend-0.1.0.jar" (
    echo [ERROR] Backend jar not found. Please build first:
    echo   cd backend ^&^& mvn package -DskipTests
    pause
    exit /b 1
)

REM Check Tesseract is installed
if not exist "C:\Apps\common\TesseractOCR\tessdata\chi_sim.traineddata" (
    echo [WARNING] Tesseract OCR language pack not found at:
    echo   C:\Apps\common\TesseractOCR\tessdata\chi_sim.traineddata
    echo OCR may not work correctly.
    echo.
)

start "ID995A RAG Service" /min "%~dp0rag-service\run-dev-log.cmd"
timeout /t 3 /nobreak >nul

start "ID988A OCR Service" /min "%~dp0ocr-service\run-dev.cmd"
timeout /t 3 /nobreak >nul

start "ID995A Backend" /min "%~dp0backend\run-jar-dev.cmd"
timeout /t 5 /nobreak >nul

start "ID995A Frontend" /min "%~dp0frontend\run-dev.cmd"

echo.
echo Services are starting...
echo Frontend : http://127.0.0.1:5173
echo Backend  : http://127.0.0.1:8080/api/health
echo RAG      : http://127.0.0.1:8090/health
echo OCR      : http://127.0.0.1:8091/health
echo.
echo Logs:
echo   rag-service\rag-dev.log
echo   ocr-service\ocr-service-dev.log
echo   backend\backend-dev.out.log
echo   frontend\frontend-dev.out.log
echo.
goto end

:docker
echo.
echo Starting DOCKER mode...

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop first.
    pause
    exit /b 1
)

docker-compose up --build -d
if errorlevel 1 (
    echo [ERROR] Docker compose failed.
    pause
    exit /b 1
)

timeout /t 5 /nobreak >nul
echo.
docker-compose ps
echo.
echo Frontend : http://localhost:5173
echo Backend  : http://localhost:8080/api/health
echo RAG      : http://localhost:8090/health
echo OCR      : http://localhost:8091/health
echo.
echo To stop  : run-docker-stop.cmd
echo To logs  : docker-compose logs -f
echo.
goto end

:end
pause
