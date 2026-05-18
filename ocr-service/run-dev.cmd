@echo off
cd /d "%~dp0"

if "%PP_OCR_DET_URL%"=="" set PP_OCR_DET_URL=http://192.168.20.250:8001/predict
if "%PP_OCR_REC_URL%"=="" set PP_OCR_REC_URL=http://192.168.20.250:8002/predict
if "%PP_OCR_TIMEOUT_SECONDS%"=="" set PP_OCR_TIMEOUT_SECONDS=60

set PYTHON_EXE=python
if exist "%~dp0.venv\Scripts\python.exe" set PYTHON_EXE=%~dp0.venv\Scripts\python.exe

"%PYTHON_EXE%" -m uvicorn app.main:app --host 127.0.0.1 --port 8091 >> "%~dp0ocr-service-dev.log" 2>&1
