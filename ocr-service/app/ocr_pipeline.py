from __future__ import annotations

import base64
import io
import json
import os
from dataclasses import dataclass
from typing import Any, Iterable

MULTILINE_FIELD_TYPES = {"multiline_text", "repeatable_text", "repeatable_date_month"}
VISUAL_PRESENCE_TYPES = {"checkbox", "image_presence", "signature_presence"}


def should_use_detection(field: dict[str, Any]) -> bool:
    return str(field.get("field_type") or field.get("fieldType") or "").strip() in MULTILINE_FIELD_TYPES


def extract_text_from_rec_response(payload: Any) -> tuple[str, float]:
    texts: list[str] = []
    confidences: list[float] = []
    _collect_rec_values(payload, texts, confidences)
    deduped = _dedupe(texts)
    confidence = max(confidences) if confidences else (0.0 if not deduped else 0.42)
    return " ".join(deduped), _clamp(confidence)


def extract_boxes_from_det_response(payload: Any) -> list[list[int]]:
    boxes: list[list[int]] = []
    _collect_boxes(payload, boxes)
    unique = []
    seen = set()
    for box in boxes:
        key = tuple(box)
        if key not in seen and box[2] > box[0] and box[3] > box[1]:
            seen.add(key)
            unique.append(box)
    return sorted(unique, key=lambda box: (box[1] // 12, box[0]))


@dataclass(frozen=True)
class PageImage:
    page: int
    image: Any
    image_width: int
    image_height: int
    source_image_data_url: str


class PaddleEndpointClient:
    def __init__(self, det_url: str, rec_url: str, timeout_seconds: float = 60.0) -> None:
        self.det_url = det_url
        self.rec_url = rec_url
        self.timeout_seconds = timeout_seconds

    def detect(self, image_bytes: bytes) -> list[list[int]]:
        response = self._post_image(self.det_url, image_bytes)
        return extract_boxes_from_det_response(response)

    def recognize(self, image_bytes: bytes) -> tuple[str, float]:
        response = self._post_image(self.rec_url, image_bytes)
        return extract_text_from_rec_response(response)

    def _trust_env(self) -> bool:
        return False

    def _post_image(self, url: str, image_bytes: bytes) -> Any:
        import httpx

        files = {"file": ("crop.jpg", image_bytes, "image/jpeg")}
        with httpx.Client(timeout=self.timeout_seconds, trust_env=self._trust_env()) as client:
            response = client.post(url, files=files)
        response.raise_for_status()
        return response.json()


def recognize_document(
    *,
    task_id: str,
    template_id: str,
    file_bytes: bytes,
    fields: list[dict[str, Any]],
    model_client: PaddleEndpointClient,
    render_dpi: int = 180,
) -> dict[str, Any]:
    pages = render_pages(file_bytes, render_dpi)
    pages_by_number = {page.page: page for page in pages}
    results: list[dict[str, Any]] = []
    for field in fields:
        page = pages_by_number.get(int(field.get("page", 0)))
        if page is None:
            continue
        results.append(recognize_field(page, field, model_client))
    return {
        "task_id": task_id,
        "template_id": template_id,
        "page_count": len(pages),
        "pages": [
            {
                "page": page.page,
                "image_width": page.image_width,
                "image_height": page.image_height,
                "source_image_data_url": page.source_image_data_url,
            }
            for page in pages
        ],
        "fields": results,
    }


def recognize_field(page: PageImage, field: dict[str, Any], model_client: PaddleEndpointClient) -> dict[str, Any]:
    value_box = field.get("value_box") or field.get("valueBox") or {}
    bbox = normalized_rect_to_bbox(value_box, page.image_width, page.image_height)
    crop = crop_image(page.image, bbox, padding=field_padding(field))
    field_type = str(field.get("field_type") or field.get("fieldType") or "")
    if field_type == "checkbox":
        checked = visual_mark_present(crop, threshold=0.020, inset_ratio=0.22)
        return field_result(field, "checked" if checked else "unchecked", checked, 0.88 if checked else 0.70, bbox, "visual_checkbox")
    if field_type in {"image_presence", "signature_presence"}:
        present = visual_mark_present(crop, threshold=0.018, inset_ratio=0.08)
        return field_result(field, "present" if present else "missing", present, 0.80 if present else 0.0, bbox, "visual_presence")
    if should_use_detection(field):
        text, confidence = detect_then_recognize(crop, model_client)
        return field_result(field, text, bool(text.strip()), confidence, bbox, "ppocr_det_rec")
    text, confidence = model_client.recognize(image_to_jpeg_bytes(crop))
    return field_result(field, text, bool(text.strip()), confidence, bbox, "ppocr_rec")


def detect_then_recognize(image: Any, model_client: PaddleEndpointClient) -> tuple[str, float]:
    boxes = model_client.detect(image_to_jpeg_bytes(image))
    if not boxes:
        return model_client.recognize(image_to_jpeg_bytes(image))
    texts: list[str] = []
    confidences: list[float] = []
    for box in boxes:
        crop = crop_image(image, box, padding=2)
        text, confidence = model_client.recognize(image_to_jpeg_bytes(crop))
        if text:
            texts.append(text)
            confidences.append(confidence)
    return "\n".join(_dedupe(texts)), _clamp(max(confidences) if confidences else 0.0)


def render_pages(file_bytes: bytes, dpi: int) -> list[PageImage]:
    from PIL import Image

    if file_bytes.lstrip().startswith(b"%PDF"):
        return render_pdf_pages(file_bytes, dpi)
    image = Image.open(io.BytesIO(file_bytes)).convert("RGB")
    return [PageImage(1, image, image.width, image.height, image_data_url(image))]


def render_pdf_pages(file_bytes: bytes, dpi: int) -> list[PageImage]:
    import pypdfium2 as pdfium

    pdf = pdfium.PdfDocument(file_bytes)
    scale = dpi / 72.0
    pages: list[PageImage] = []
    try:
        for index in range(len(pdf)):
            bitmap = pdf[index].render(scale=scale)
            image = bitmap.to_pil().convert("RGB")
            pages.append(PageImage(index + 1, image, image.width, image.height, image_data_url(image)))
    finally:
        pdf.close()
    return pages


def normalized_rect_to_bbox(rect: dict[str, Any], image_width: int, image_height: int) -> list[int]:
    x = float(rect.get("x", 0))
    y = float(rect.get("y", 0))
    width = float(rect.get("width", 0))
    height = float(rect.get("height", 0))
    x0 = round(x * image_width)
    y0 = round(y * image_height)
    x1 = round((x + width) * image_width)
    y1 = round((y + height) * image_height)
    return [max(0, x0), max(0, y0), min(image_width, max(x0, x1)), min(image_height, max(y0, y1))]


def crop_image(image: Any, bbox: list[int], padding: int = 0) -> Any:
    x0, y0, x1, y1 = bbox
    padded = [
        max(0, x0 - padding),
        max(0, y0 - padding),
        min(image.width, x1 + padding),
        min(image.height, y1 + padding),
    ]
    if padded[2] <= padded[0] or padded[3] <= padded[1]:
        return image.crop((0, 0, 1, 1))
    return image.crop(tuple(padded))


def visual_mark_present(image: Any, threshold: float, inset_ratio: float) -> bool:
    gray = image.convert("L")
    width, height = gray.size
    inset_x = round(width * inset_ratio)
    inset_y = round(height * inset_ratio)
    region = gray.crop((inset_x, inset_y, max(inset_x + 1, width - inset_x), max(inset_y + 1, height - inset_y)))
    pixels = list(region.getdata())
    if not pixels:
        return False
    dark = sum(1 for value in pixels if value < 170)
    return dark / len(pixels) >= threshold


def image_to_jpeg_bytes(image: Any) -> bytes:
    output = io.BytesIO()
    image.convert("RGB").save(output, format="JPEG", quality=95)
    return output.getvalue()


def image_data_url(image: Any) -> str:
    data = base64.b64encode(image_to_jpeg_bytes(image)).decode("ascii")
    return f"data:image/jpeg;base64,{data}"


def field_padding(field: dict[str, Any]) -> int:
    field_type = str(field.get("field_type") or field.get("fieldType") or "")
    if field_type in {"date_cells", "number_cells"}:
        return 2
    return 6


def field_result(
    field: dict[str, Any],
    value: str,
    present: bool,
    confidence: float,
    bbox: list[int],
    source: str,
) -> dict[str, Any]:
    return {
        "key": field.get("key", ""),
        "value": value if isinstance(value, str) else "",
        "present": bool(present),
        "confidence": _clamp(confidence),
        "bbox": bbox,
        "source": source,
    }


def parse_fields(fields_json: str) -> list[dict[str, Any]]:
    value = json.loads(fields_json or "[]")
    if not isinstance(value, list):
        raise ValueError("fields must be a JSON array")
    return [item for item in value if isinstance(item, dict)]


def default_model_client() -> PaddleEndpointClient:
    return PaddleEndpointClient(
        det_url=os.getenv("PP_OCR_DET_URL", "http://192.168.20.250:8001/predict"),
        rec_url=os.getenv("PP_OCR_REC_URL", "http://192.168.20.250:8002/predict"),
        timeout_seconds=float(os.getenv("PP_OCR_TIMEOUT_SECONDS", "60")),
    )


def _collect_rec_values(payload: Any, texts: list[str], confidences: list[float]) -> None:
    if isinstance(payload, str):
        if payload.strip():
            texts.append(payload)
        return
    if isinstance(payload, list):
        if payload and isinstance(payload[0], str):
            if payload[0].strip():
                texts.append(payload[0])
            if len(payload) > 1 and isinstance(payload[1], (int, float)):
                confidences.append(float(payload[1]))
            return
        for item in payload:
            _collect_rec_values(item, texts, confidences)
        return
    if not isinstance(payload, dict):
        return
    for key in ("text", "transcription", "rec_text", "label", "value"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            texts.append(value)
    for key in ("score", "confidence", "probability", "prob"):
        value = payload.get(key)
        if isinstance(value, (int, float)):
            confidences.append(float(value))
    for value in payload.values():
        if isinstance(value, (dict, list)):
            _collect_rec_values(value, texts, confidences)


def _collect_boxes(payload: Any, boxes: list[list[int]]) -> None:
    if isinstance(payload, dict):
        parsed_boxes = _parse_box_list(payload.get("boxes"))
        boxes.extend(parsed_boxes)
        for key in ("bbox", "box", "rect"):
            parsed = _parse_bbox(payload.get(key))
            if parsed:
                boxes.append(parsed)
        for key in ("points", "poly", "polygon"):
            parsed = _parse_points(payload.get(key))
            if parsed:
                boxes.append(parsed)
        for value in payload.values():
            if isinstance(value, (dict, list)):
                _collect_boxes(value, boxes)
        return
    if isinstance(payload, list):
        parsed = _parse_points(payload)
        if parsed:
            boxes.append(parsed)
            return
        for item in payload:
            _collect_boxes(item, boxes)


def _parse_bbox(value: Any) -> list[int] | None:
    if not isinstance(value, list) or len(value) != 4:
        return None
    if not all(isinstance(item, (int, float)) for item in value):
        return None
    x0, y0, x1, y1 = [round(float(item)) for item in value]
    return [min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)]


def _parse_points(value: Any) -> list[int] | None:
    if not isinstance(value, list) or not value:
        return None
    points: list[tuple[float, float]] = []
    for point in value:
        if not isinstance(point, list) or len(point) < 2:
            return None
        if not isinstance(point[0], (int, float)) or not isinstance(point[1], (int, float)):
            return None
        points.append((float(point[0]), float(point[1])))
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return [round(min(xs)), round(min(ys)), round(max(xs)), round(max(ys))]


def _parse_box_list(value: Any) -> list[list[int]]:
    if not isinstance(value, list):
        return []
    parsed: list[list[int]] = []
    for item in value:
        box = _parse_bbox(item) or _parse_points(item)
        if box:
            parsed.append(box)
    return parsed


def _dedupe(values: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen = set()
    for value in values:
        normalized = " ".join(str(value).split())
        if normalized and normalized not in seen:
            seen.add(normalized)
            result.append(str(value))
    return result


def _clamp(value: float) -> float:
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return 0.0
    if numeric < 0:
        return 0.0
    if numeric > 1:
        return 1.0
    return numeric
