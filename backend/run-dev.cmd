@echo off
cd /d "%~dp0"
set RAG_ENABLED=true
set RAG_BASE_URL=http://127.0.0.1:8090
set FIELD_OCR_BASE_URL=http://127.0.0.1:8091
set FIELD_OCR_TIMEOUT_SECONDS=300
set FIELD_OCR_RENDER_DPI=300
set OCR_TASKS_DIR=%~dp0..\uploads\ocr-tasks
mvn.cmd spring-boot:run >> "%~dp0backend-dev.out.log" 2>&1
