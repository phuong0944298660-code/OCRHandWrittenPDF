from __future__ import annotations

from fastapi import FastAPI, File, Form, HTTPException, UploadFile

from app.ocr_pipeline import default_model_client, parse_fields, recognize_document


app = FastAPI(title="ID988A Field OCR Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "id988a-field-ocr-service"}


@app.post("/ocr/recognize")
async def recognize(
    file: UploadFile = File(...),
    task_id: str = Form(...),
    template_id: str = Form(...),
    fields: str = Form(...),
) -> dict:
    try:
        field_defs = parse_fields(fields)
        file_bytes = await file.read()
        return recognize_document(
            task_id=task_id,
            template_id=template_id,
            file_bytes=file_bytes,
            fields=field_defs,
            model_client=default_model_client(),
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
