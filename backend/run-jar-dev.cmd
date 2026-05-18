@echo off
cd /d "%~dp0"

REM OCR is core capability - must be enabled
set OCR_ENABLED=true
set OCR_LANGUAGE=eng+chi_sim+chi_tra
set TESSDATA_PREFIX=C:/Apps/common/TesseractOCR/tessdata

REM RAG service connection
set RAG_ENABLED=true
set RAG_BASE_URL=http://127.0.0.1:8090

REM Python field OCR service connection
set FIELD_OCR_BASE_URL=http://127.0.0.1:8091
set FIELD_OCR_TIMEOUT_SECONDS=300
set OCR_TASKS_DIR=%~dp0..\uploads\ocr-tasks

REM LLM disabled by default for demo
set LLM_ENABLED=false

java -jar "%~dp0target\id995a-review-backend-0.1.0.jar" >> "%~dp0backend-dev.out.log" 2>&1
