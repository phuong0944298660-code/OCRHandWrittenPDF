@echo off
cd /d "%~dp0"

if "%PP_OCR_DET_MODEL_DIR%"=="" set PP_OCR_DET_MODEL_DIR=%~dp0..\models\PP-OCRv5_server_det_infer
if "%PP_OCR_REC_MODEL_DIR%"=="" set PP_OCR_REC_MODEL_DIR=%~dp0..\models\PP-OCRv5_server_rec_infer
if "%PP_OCR_DEVICE%"=="" set PP_OCR_DEVICE=cpu
if "%PP_OCR_CPU_THREADS%"=="" set PP_OCR_CPU_THREADS=4
if "%OCR_RENDER_DPI%"=="" set OCR_RENDER_DPI=300
if "%PADDLE_PDX_CACHE_HOME%"=="" set PADDLE_PDX_CACHE_HOME=%~dp0..\.paddlex_cache

set PYTHON_EXE=python
if exist "%~dp0.venv\Scripts\python.exe" set PYTHON_EXE=%~dp0.venv\Scripts\python.exe

"%PYTHON_EXE%" -m uvicorn app.main:app --host 127.0.0.1 --port 8091 >> "%~dp0ocr-service-dev.log" 2>&1
