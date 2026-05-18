from __future__ import annotations

import base64
import io
import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Protocol

MULTILINE_FIELD_TYPES = {"multiline_text", "repeatable_text", "repeatable_date_month"}
VISUAL_PRESENCE_TYPES = {"checkbox", "image_presence"}
SUPPORTED_RENDER_DPI = {300, 400}
DEFAULT_RENDER_DPI = 300
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CACHE_DIR = PROJECT_ROOT / ".paddlex_cache"
DEFAULT_DET_MODEL_DIR = PROJECT_ROOT / "models" / "PP-OCRv5_server_det_infer"
DEFAULT_REC_MODEL_DIR = PROJECT_ROOT / "models" / "PP-OCRv5_server_rec_infer"
LEFT_LABEL_TRIM_BY_KEY = {
    "surnameEn.value": 0.55,
    "givenNamesEn.value": 0.55,
    "maidenSurname.value": 0.55,
    "nameChinese.value": 0.55,
    "dateOfBirth.value": 0.45,
    "placeOfBirth.value": 0.30,
    "nationality.value": 0.42,
    "occupation.value": 1.05,
    "travelDocument.type.value": 0.50,
    "travelDocument.placeOfIssue.value": 0.42,
    "travelDocument.dateOfIssue.value": 0.35,
    "travelDocument.dateOfExpiry.value": 0.35,
}
BOTTOM_LABEL_TRIM_BY_FIELD_TYPE = {
    "date_cells": 0.32,
    "number_cells": 0.25,
}


def configure_paddlex_environment() -> None:
    os.environ.setdefault("PADDLE_PDX_CACHE_HOME", str(DEFAULT_CACHE_DIR))


configure_paddlex_environment()


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


@dataclass(frozen=True)
class FieldWorkItem:
    index: int
    field: dict[str, Any]
    bbox: list[int]
    crop: Any
    ocr_crop: Any
    roi_image_data_url: str


@dataclass(frozen=True)
class PageAlignmentResult:
    aligned: bool
    reasons: list[str]


@dataclass(frozen=True)
class CheckboxDetection:
    found: bool
    checked: bool
    confidence: float


class PaddleModelClient(Protocol):
    def detect(self, image_bytes: bytes) -> list[list[int]]:
        ...

    def recognize(self, image_bytes: bytes) -> tuple[str, float]:
        ...

    def detect_many(self, image_bytes_list: list[bytes]) -> list[list[list[int]]]:
        ...

    def recognize_many(self, image_bytes_list: list[bytes]) -> list[tuple[str, float]]:
        ...


class LocalPaddleModelClient:
    def __init__(
        self,
        *,
        det_model_dir: str,
        rec_model_dir: str,
        device: str = "cpu",
        cpu_threads: int = 4,
    ) -> None:
        configure_paddlex_environment()
        self.det_model_dir = det_model_dir
        self.rec_model_dir = rec_model_dir
        self.device = device
        self.cpu_threads = cpu_threads
        self._detector = None
        self._recognizer = None

    def detect(self, image_bytes: bytes) -> list[list[int]]:
        response = self._detector_model().predict(image_bytes_to_array(image_bytes))
        return extract_boxes_from_det_response(response)

    def detect_many(self, image_bytes_list: list[bytes]) -> list[list[list[int]]]:
        if not image_bytes_list:
            return []
        response = self._detector_model().predict([image_bytes_to_array(image) for image in image_bytes_list])
        return _pad_list(
            [extract_boxes_from_det_response(item) for item in response],
            len(image_bytes_list),
            [],
        )

    def recognize(self, image_bytes: bytes) -> tuple[str, float]:
        response = self._recognizer_model().predict(image_bytes_to_array(image_bytes))
        return extract_text_from_rec_response(response)

    def recognize_many(self, image_bytes_list: list[bytes]) -> list[tuple[str, float]]:
        if not image_bytes_list:
            return []
        response = self._recognizer_model().predict([image_bytes_to_array(image) for image in image_bytes_list])
        return _pad_list(
            [extract_text_from_rec_response(item) for item in response],
            len(image_bytes_list),
            ("", 0.0),
        )

    def _detector_model(self) -> Any:
        if self._detector is None:
            from paddleocr import TextDetection

            self._detector = TextDetection(
                model_dir=self.det_model_dir,
                device=self.device,
                cpu_threads=self.cpu_threads,
            )
        return self._detector

    def _recognizer_model(self) -> Any:
        if self._recognizer is None:
            from paddleocr import TextRecognition

            self._recognizer = TextRecognition(
                model_dir=self.rec_model_dir,
                device=self.device,
                cpu_threads=self.cpu_threads,
            )
        return self._recognizer


def recognize_document(
    *,
    task_id: str,
    template_id: str,
    file_bytes: bytes,
    fields: list[dict[str, Any]],
    model_client: PaddleModelClient,
    render_dpi: int = DEFAULT_RENDER_DPI,
) -> dict[str, Any]:
    pages = render_pages(file_bytes, render_dpi)
    pages_by_number = {page.page: page for page in pages}
    result_slots: list[dict[str, Any] | None] = [None] * len(fields)
    for page_number, page in pages_by_number.items():
        indexed_fields = [
            (index, field)
            for index, field in enumerate(fields)
            if _field_page(field) == page_number
        ]
        if not indexed_fields:
            continue
        alignment = validate_page_alignment(page)
        if not alignment.aligned:
            for index, field in indexed_fields:
                result_slots[index] = alignment_failed_field_result(page, field, alignment)
            continue
        page_results = recognize_page_fields(
            page,
            [field for _, field in indexed_fields],
            model_client,
        )
        for (index, _), result in zip(indexed_fields, page_results):
            result_slots[index] = result
    results = [result for result in result_slots if result is not None]
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


def recognize_page_fields(
    page: PageImage,
    fields: list[dict[str, Any]],
    model_client: PaddleModelClient,
) -> list[dict[str, Any]]:
    results: list[dict[str, Any] | None] = [None] * len(fields)
    single_line_items: list[FieldWorkItem] = []
    multiline_items: list[FieldWorkItem] = []

    for index, field in enumerate(fields):
        item = field_work_item(index, page, field)
        field_type = str(field.get("field_type") or field.get("fieldType") or "")
        if field_type == "checkbox":
            detection = checkbox_mark_present(page.image, item.bbox)
            checked = detection.checked
            results[index] = field_result(field, "checked" if checked else "unchecked", checked, detection.confidence, item.bbox, "visual_checkbox", item.roi_image_data_url)
            continue
        if field_type == "image_presence":
            present = visual_mark_present(item.crop, threshold=0.018, inset_ratio=0.08)
            results[index] = field_result(field, "present" if present else "missing", present, 0.80 if present else 0.0, item.bbox, "visual_presence", item.roi_image_data_url)
            continue
        if not roi_has_user_content(item.ocr_crop):
            results[index] = field_result(field, "", False, 0.0, item.bbox, "empty_roi", item.roi_image_data_url)
            continue
        if should_use_detection(field):
            multiline_items.append(item)
        else:
            single_line_items.append(item)

    recognize_single_line_items(single_line_items, model_client, results)
    recognize_multiline_items(multiline_items, model_client, results)

    return [
        result if result is not None else field_result(fields[index], "", False, 0.0, field_work_item(index, page, fields[index]).bbox, "empty_roi", field_work_item(index, page, fields[index]).roi_image_data_url)
        for index, result in enumerate(results)
    ]


def recognize_single_line_items(
    items: list[FieldWorkItem],
    model_client: PaddleModelClient,
    results: list[dict[str, Any] | None],
) -> None:
    if not items:
        return
    ocr_results = model_client.recognize_many([image_to_jpeg_bytes(item.ocr_crop) for item in items])
    for item, (text, confidence) in zip(items, ocr_results):
        text = clean_recognized_field_text(item.field, text)
        field_type = str(item.field.get("field_type") or item.field.get("fieldType") or "")
        if field_type == "signature_presence" and not text.strip() and visual_mark_present(item.crop, threshold=0.018, inset_ratio=0.08):
            results[item.index] = field_result(
                item.field,
                "present",
                True,
                0.80,
                item.bbox,
                "visual_presence",
                item.roi_image_data_url,
            )
            continue
        results[item.index] = field_result(
            item.field,
            text,
            bool(text.strip()),
            confidence,
            item.bbox,
            "ppocr_rec",
            item.roi_image_data_url,
        )


def recognize_multiline_items(
    items: list[FieldWorkItem],
    model_client: PaddleModelClient,
    results: list[dict[str, Any] | None],
) -> None:
    if not items:
        return
    detected_boxes = model_client.detect_many([image_to_jpeg_bytes(item.ocr_crop) for item in items])
    child_crops: list[bytes] = []
    child_owners: list[int] = []
    child_boxes: list[list[int] | None] = []
    fallback_items: list[FieldWorkItem] = []
    for item, boxes in zip(items, detected_boxes):
        if not boxes:
            fallback_items.append(item)
            continue
        for box in boxes:
            child_crops.append(image_to_jpeg_bytes(crop_image(item.ocr_crop, box, padding=2)))
            child_owners.append(item.index)
            child_boxes.append(box)

    recognized_children = model_client.recognize_many(child_crops)
    entries_by_index: dict[int, list[dict[str, Any]]] = {item.index: [] for item in items}
    fields_by_index = {item.index: item.field for item in items}
    for owner, box, (text, confidence) in zip(child_owners, child_boxes, recognized_children):
        owner_field = fields_by_index.get(owner)
        if text and should_keep_child_text(owner_field, text):
            entries_by_index[owner].append({"text": text, "confidence": confidence, "box": box})

    fallback_results = model_client.recognize_many([image_to_jpeg_bytes(item.ocr_crop) for item in fallback_items]) if fallback_items else []
    for item, (text, confidence) in zip(fallback_items, fallback_results):
        if text and should_keep_child_text(item.field, text):
            entries_by_index[item.index].append({"text": text, "confidence": confidence, "box": None})

    for item in items:
        entries = entries_by_index[item.index]
        text = format_multiline_field_text(item.field, entries, item.ocr_crop.height)
        text = clean_recognized_field_text(item.field, text)
        confidences = [entry["confidence"] for entry in entries]
        results[item.index] = field_result(
            item.field,
            text,
            bool(text.strip()),
            max(confidences) if confidences else 0.0,
            item.bbox,
            "ppocr_det_rec",
            item.roi_image_data_url,
        )


def field_work_item(index: int, page: PageImage, field: dict[str, Any]) -> FieldWorkItem:
    value_box = field.get("value_box") or field.get("valueBox") or {}
    bbox = normalized_rect_to_bbox(value_box, page.image_width, page.image_height)
    crop = crop_image(page.image, bbox, padding=field_padding(field))
    return FieldWorkItem(index, field, bbox, crop, prepare_text_recognition_crop(crop, field), image_data_url(crop))


def alignment_failed_field_result(
    page: PageImage,
    field: dict[str, Any],
    alignment: PageAlignmentResult,
) -> dict[str, Any]:
    item = field_work_item(0, page, field)
    return field_result(
        field,
        "",
        True,
        0.0,
        item.bbox,
        "page_alignment_failed",
        item.roi_image_data_url,
    ) | {"alignment_reasons": alignment.reasons}


def validate_page_alignment(page: PageImage) -> PageAlignmentResult:
    reasons: list[str] = []
    if page.image_width <= 0 or page.image_height <= 0:
        reasons.append("invalid_page_dimensions")
    elif page.image_width >= page.image_height:
        reasons.append("page_not_portrait")
    else:
        aspect_ratio = page.image_width / page.image_height
        if aspect_ratio < 0.64 or aspect_ratio > 0.78:
            reasons.append("unexpected_page_aspect_ratio")
    return PageAlignmentResult(not reasons, reasons)


def _field_page(field: dict[str, Any]) -> int:
    try:
        return int(field.get("page", 0))
    except (TypeError, ValueError):
        return 0


def _legacy_recognize_document(
    *,
    task_id: str,
    template_id: str,
    file_bytes: bytes,
    fields: list[dict[str, Any]],
    model_client: PaddleModelClient,
    render_dpi: int = DEFAULT_RENDER_DPI,
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


def recognize_field(page: PageImage, field: dict[str, Any], model_client: PaddleModelClient) -> dict[str, Any]:
    value_box = field.get("value_box") or field.get("valueBox") or {}
    bbox = normalized_rect_to_bbox(value_box, page.image_width, page.image_height)
    crop = crop_image(page.image, bbox, padding=field_padding(field))
    roi_image_data_url = image_data_url(crop)
    field_type = str(field.get("field_type") or field.get("fieldType") or "")
    if field_type == "checkbox":
        detection = checkbox_mark_present(page.image, bbox)
        checked = detection.checked
        return field_result(field, "checked" if checked else "unchecked", checked, detection.confidence, bbox, "visual_checkbox", roi_image_data_url)
    if field_type == "image_presence":
        present = visual_mark_present(crop, threshold=0.018, inset_ratio=0.08)
        return field_result(field, "present" if present else "missing", present, 0.80 if present else 0.0, bbox, "visual_presence", roi_image_data_url)
    if should_use_detection(field):
        text, confidence = detect_then_recognize(crop, model_client)
        return field_result(field, text, bool(text.strip()), confidence, bbox, "ppocr_det_rec", roi_image_data_url)
    text, confidence = model_client.recognize(image_to_jpeg_bytes(crop))
    return field_result(field, text, bool(text.strip()), confidence, bbox, "ppocr_rec", roi_image_data_url)


def detect_then_recognize(image: Any, model_client: PaddleModelClient) -> tuple[str, float]:
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


def prepare_text_recognition_crop(image: Any, field: dict[str, Any]) -> Any:
    field_type = str(field.get("field_type") or field.get("fieldType") or "")
    if field_type in VISUAL_PRESENCE_TYPES:
        return image

    crop = image.convert("RGB")
    width, height = crop.size
    if width <= 1 or height <= 1:
        return crop

    key = str(field.get("key") or "")
    left_trim = _recognition_left_trim_pixels(key, width, height)
    bottom_trim = 0 if key in {"workingExperience.totalYears.value", "workingExperience.totalMonths.value"} else _recognition_bottom_trim_pixels(field_type, width, height)
    if left_trim or bottom_trim:
        crop = crop.crop((
            min(left_trim, max(0, width - 1)),
            0,
            width,
            max(1, height - bottom_trim),
        ))

    return suppress_form_lines(crop)


def should_keep_child_text(field: dict[str, Any] | None, text: str) -> bool:
    if field is None:
        return bool(text.strip())
    key = str(field.get("key") or "")
    normalized = text.strip().lower()
    if key == "occupation.value" and normalized in {"on", "ion", "occupation"}:
        return False
    if key == "placeOfBirth.value" and normalized in {"th", "rth", "irth", "birth", "place", "of"}:
        return False
    return bool(normalized)


def format_multiline_field_text(field: dict[str, Any], entries: list[dict[str, Any]], crop_height: int) -> str:
    texts = [str(entry.get("text") or "") for entry in entries if str(entry.get("text") or "").strip()]
    if not texts:
        return ""
    key = str(field.get("key") or "")
    if not key.startswith("workingExperience.items[]"):
        return "\n".join(_dedupe(texts))

    positioned = [entry for entry in entries if entry.get("box")]
    if not positioned:
        return "\n".join(_dedupe(texts))

    sorted_entries = sorted(positioned, key=lambda entry: (((entry["box"][1] + entry["box"][3]) / 2), entry["box"][0]))
    split_at = len(sorted_entries)
    if len(sorted_entries) >= 2:
        centers = [(entry["box"][1] + entry["box"][3]) / 2 for entry in sorted_entries]
        gaps = [centers[index + 1] - centers[index] for index in range(len(centers) - 1)]
        largest_gap = max(gaps)
        if largest_gap >= max(28, crop_height * 0.10):
            split_at = gaps.index(largest_gap) + 1
    grouped: list[list[dict[str, Any]]] = [sorted_entries[:split_at], sorted_entries[split_at:]]

    parts: list[str] = []
    for group in grouped:
        if not group:
            continue
        group_texts = [
            str(entry.get("text") or "").strip()
            for entry in sorted(group, key=lambda entry: (entry["box"][1], entry["box"][0]))
            if str(entry.get("text") or "").strip()
        ]
        joined = " ".join(_dedupe(group_texts)).strip()
        if joined:
            parts.append(joined)
    return "|".join(parts) if parts else "\n".join(_dedupe(texts))


def clean_recognized_field_text(field: dict[str, Any], text: str) -> str:
    key = str(field.get("key") or "")
    if key == "contactTelephone.no.value":
        return re.sub(r"^\s*(?:no\.?|n0\.?)\s*", "", text, flags=re.IGNORECASE).strip()
    if key == "undertaking.contractNo.value":
        return re.sub(r"^\s*(?:no\.?|n0\.?)\s*", "", text, flags=re.IGNORECASE).strip()
    if key.endswith("Confirmation.date.value") or key == "undertaking.date.value":
        return re.sub(r"^\s*(?:日期|date)\s*", "", text, flags=re.IGNORECASE).strip()
    if key == "travelDocument.placeOfIssue.value":
        return text.strip().removeprefix("e").removeprefix("E")
    return text


def _recognition_left_trim_pixels(key: str, width: int, height: int) -> int:
    multiplier = LEFT_LABEL_TRIM_BY_KEY.get(key, 0.0)
    if multiplier <= 0:
        return 0
    return max(0, min(width - 1, round(height * multiplier)))


def _recognition_bottom_trim_pixels(field_type: str, width: int, height: int) -> int:
    ratio = BOTTOM_LABEL_TRIM_BY_FIELD_TYPE.get(field_type, 0.0)
    if ratio <= 0:
        return 0
    return max(0, min(height - 1, round(height * ratio)))


def suppress_form_lines(image: Any) -> Any:
    import numpy as np
    from PIL import Image

    rgb = image.convert("RGB")
    gray = np.array(rgb.convert("L"))
    if gray.size == 0:
        return rgb

    dark = gray < 190
    if not dark.any():
        return rgb

    row_ratio = dark.mean(axis=1)
    col_ratio = dark.mean(axis=0)
    line_dark = gray < 230
    row_ratio = line_dark.mean(axis=1)
    col_ratio = line_dark.mean(axis=0)
    line_mask = (row_ratio >= 0.70)[:, None] | (col_ratio >= 0.70)[None, :]
    if not line_mask.any():
        return rgb

    pixels = np.array(rgb)
    pixels[line_mask] = 255
    return Image.fromarray(pixels)


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


def checkbox_mark_present(page_image: Any, bbox: list[int]) -> CheckboxDetection:
    width = max(1, bbox[2] - bbox[0])
    height = max(1, bbox[3] - bbox[1])
    expected_side = max(width, height)

    exact_crop = crop_image(page_image, bbox, padding=0)
    exact = detect_checkbox_mark(exact_crop, expected_side=expected_side)
    if exact.found:
        return exact

    padding = max(8, round(expected_side * 0.85))
    search_bbox = [
        max(0, bbox[0] - padding),
        max(0, bbox[1] - padding),
        min(page_image.width, bbox[2] + padding),
        min(page_image.height, bbox[3] + padding),
    ]
    search_crop = crop_image(page_image, search_bbox, padding=0)
    target_center = (
        (bbox[0] + bbox[2]) / 2 - search_bbox[0],
        (bbox[1] + bbox[3]) / 2 - search_bbox[1],
    )
    expanded = detect_checkbox_mark(search_crop, expected_side=expected_side, target_center=target_center)
    if expanded.found:
        return expanded

    return CheckboxDetection(False, False, 0.70)


def detect_checkbox_mark(
    image: Any,
    *,
    expected_side: int,
    target_center: tuple[float, float] | None = None,
) -> CheckboxDetection:
    import numpy as np

    gray = np.array(image.convert("L"))
    if gray.size == 0:
        return CheckboxDetection(False, False, 0.70)

    dark = gray < 170
    if not dark.any():
        return CheckboxDetection(False, False, 0.70)

    candidates = checkbox_square_candidates(dark, expected_side=expected_side, target_center=target_center)
    if not candidates:
        return CheckboxDetection(False, False, 0.70)

    candidate = candidates[0]
    x0, y0, x1, y1 = candidate["bbox"]
    side = max(1, min(x1 - x0, y1 - y0))
    margin = max(2, round(side * 0.18))
    inner_x0 = min(x1, x0 + margin)
    inner_y0 = min(y1, y0 + margin)
    inner_x1 = max(inner_x0 + 1, x1 - margin)
    inner_y1 = max(inner_y0 + 1, y1 - margin)
    inner = dark[inner_y0:inner_y1, inner_x0:inner_x1]
    inner_dark = int(inner.sum())
    inner_area = int(inner.size)
    checked = inner_dark >= max(5, round(inner_area * 0.030))
    return CheckboxDetection(True, checked, 0.88 if checked else 0.86)


def checkbox_square_candidates(
    dark: Any,
    *,
    expected_side: int,
    target_center: tuple[float, float] | None,
) -> list[dict[str, Any]]:
    import numpy as np

    height, width = dark.shape
    visited = np.zeros(dark.shape, dtype=bool)
    ys, xs = np.nonzero(dark)
    min_side = max(8, round(expected_side * 0.40))
    max_side = max(min_side + 1, round(expected_side * 1.35))
    candidates: list[dict[str, Any]] = []

    for start_y, start_x in zip(ys.tolist(), xs.tolist()):
        if visited[start_y, start_x]:
            continue

        stack = [(start_x, start_y)]
        visited[start_y, start_x] = True
        count = 0
        x_min = x_max = start_x
        y_min = y_max = start_y

        while stack:
            x, y = stack.pop()
            count += 1
            x_min = min(x_min, x)
            x_max = max(x_max, x)
            y_min = min(y_min, y)
            y_max = max(y_max, y)

            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if nx < 0 or ny < 0 or nx >= width or ny >= height:
                    continue
                if visited[ny, nx] or not dark[ny, nx]:
                    continue
                visited[ny, nx] = True
                stack.append((nx, ny))

        box_width = x_max - x_min + 1
        box_height = y_max - y_min + 1
        side = max(box_width, box_height)
        if side < min_side or side > max_side:
            continue
        aspect = box_width / max(1, box_height)
        if aspect < 0.45 or aspect > 1.80:
            continue
        fill_ratio = count / max(1, box_width * box_height)
        if fill_ratio > 0.65:
            continue
        if box_width > width * 0.90 and box_height > height * 0.90:
            continue
        if not checkbox_border_like(dark, x_min, y_min, x_max + 1, y_max + 1):
            continue

        center_x = (x_min + x_max + 1) / 2
        center_y = (y_min + y_max + 1) / 2
        distance = 0.0
        if target_center is not None:
            distance = ((center_x - target_center[0]) ** 2 + (center_y - target_center[1]) ** 2) ** 0.5
        square_penalty = abs(1.0 - aspect)
        candidates.append({
            "bbox": [x_min, y_min, x_max + 1, y_max + 1],
            "area": count,
            "distance": distance,
            "square_penalty": square_penalty,
        })

    if target_center is None:
        return sorted(candidates, key=lambda item: (-item["area"], item["square_penalty"]))
    return sorted(candidates, key=lambda item: (item["distance"], item["square_penalty"], -item["area"]))


def checkbox_border_like(dark: Any, x0: int, y0: int, x1: int, y1: int) -> bool:
    region = dark[y0:y1, x0:x1]
    height, width = region.shape
    if width < 8 or height < 8:
        return False
    band = max(1, round(min(width, height) * 0.12))
    edges = [
        float(region[:band, :].mean()),
        float(region[max(0, height - band):, :].mean()),
        float(region[:, :band].mean()),
        float(region[:, max(0, width - band):].mean()),
    ]
    return sum(edge >= 0.08 for edge in edges) >= 3 and max(edges) >= 0.14


def roi_has_user_content(image: Any) -> bool:
    import numpy as np

    gray = image.convert("L")
    width, height = gray.size
    if width <= 1 or height <= 1:
        return False
    inset_x = max(0, min(round(width * 0.03), width // 4))
    inset_y = max(0, min(round(height * 0.03), height // 4))
    region = gray.crop((inset_x, inset_y, max(inset_x + 1, width - inset_x), max(inset_y + 1, height - inset_y)))
    dark = np.array(region) < 190
    if dark.size == 0:
        return False

    # Form lines occupy most of a row/column; handwriting strokes are sparse.
    row_ratio = dark.mean(axis=1)
    col_ratio = dark.mean(axis=0)
    dark[row_ratio >= 0.75, :] = False
    dark[:, col_ratio >= 0.75] = False

    remaining_dark = int(dark.sum())
    area = int(dark.size)
    return remaining_dark >= max(8, round(area * 0.0015))


def image_to_jpeg_bytes(image: Any) -> bytes:
    output = io.BytesIO()
    image.convert("RGB").save(output, format="JPEG", quality=95)
    return output.getvalue()


def image_bytes_to_array(image_bytes: bytes) -> Any:
    import numpy as np
    from PIL import Image

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    return np.array(image)


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
    roi_image_data_url: str,
) -> dict[str, Any]:
    return {
        "key": field.get("key", ""),
        "value": value if isinstance(value, str) else "",
        "present": bool(present),
        "confidence": _clamp(confidence),
        "bbox": bbox,
        "source": source,
        "roi_image_data_url": roi_image_data_url,
    }


def parse_fields(fields_json: str) -> list[dict[str, Any]]:
    value = json.loads(fields_json or "[]")
    if not isinstance(value, list):
        raise ValueError("fields must be a JSON array")
    return [item for item in value if isinstance(item, dict)]


def parse_ocr_params(ocr_params_json: str | None) -> dict[str, Any]:
    value = json.loads(ocr_params_json or "{}")
    if not isinstance(value, dict):
        raise ValueError("ocr_params must be a JSON object")
    render_dpi = normalize_render_dpi(value.get("render_dpi", os.getenv("OCR_RENDER_DPI", DEFAULT_RENDER_DPI)))
    return {"render_dpi": render_dpi}


def normalize_render_dpi(value: Any) -> int:
    try:
        dpi = int(value)
    except (TypeError, ValueError):
        raise ValueError("render_dpi must be 300 or 400") from None
    if dpi not in SUPPORTED_RENDER_DPI:
        raise ValueError("render_dpi must be 300 or 400")
    return dpi


_MODEL_CLIENT: LocalPaddleModelClient | None = None


def default_model_client() -> LocalPaddleModelClient:
    global _MODEL_CLIENT
    if _MODEL_CLIENT is None:
        _MODEL_CLIENT = LocalPaddleModelClient(
            det_model_dir=os.getenv("PP_OCR_DET_MODEL_DIR", str(DEFAULT_DET_MODEL_DIR)),
            rec_model_dir=os.getenv("PP_OCR_REC_MODEL_DIR", str(DEFAULT_REC_MODEL_DIR)),
            device=os.getenv("PP_OCR_DEVICE", "cpu"),
            cpu_threads=int(os.getenv("PP_OCR_CPU_THREADS", "4")),
        )
    return _MODEL_CLIENT


def reset_default_model_client() -> None:
    global _MODEL_CLIENT
    _MODEL_CLIENT = None


def _collect_rec_values(payload: Any, texts: list[str], confidences: list[float]) -> None:
    payload = _as_dict(_as_list(payload))
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
    for key in ("score", "confidence", "probability", "prob", "rec_score"):
        value = payload.get(key)
        if isinstance(value, (int, float)):
            confidences.append(float(value))
    for key, value in payload.items():
        if key in {"input_img", "vis_font"}:
            continue
        if isinstance(value, (dict, list)) or hasattr(value, "items"):
            _collect_rec_values(value, texts, confidences)


def _collect_boxes(payload: Any, boxes: list[list[int]]) -> None:
    payload = _as_dict(_as_list(payload))
    if isinstance(payload, dict):
        boxes.extend(_parse_box_list(payload.get("boxes")))
        boxes.extend(_parse_box_list(payload.get("dt_polys")))
        for key in ("bbox", "box", "rect"):
            parsed = _parse_bbox(payload.get(key))
            if parsed:
                boxes.append(parsed)
        for key in ("points", "poly", "polygon"):
            parsed = _parse_points(payload.get(key))
            if parsed:
                boxes.append(parsed)
        for key, value in payload.items():
            if key in {"input_img"}:
                continue
            if isinstance(value, (dict, list)) or hasattr(value, "items"):
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
    value = _as_list(value)
    if not isinstance(value, list) or len(value) != 4:
        return None
    if not all(isinstance(item, (int, float)) for item in value):
        return None
    x0, y0, x1, y1 = [round(float(item)) for item in value]
    return [min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)]


def _parse_points(value: Any) -> list[int] | None:
    value = _as_list(value)
    if not isinstance(value, list) or not value:
        return None
    points: list[tuple[float, float]] = []
    for point in value:
        point = _as_list(point)
        if not isinstance(point, list) or len(point) < 2:
            return None
        if not isinstance(point[0], (int, float)) or not isinstance(point[1], (int, float)):
            return None
        points.append((float(point[0]), float(point[1])))
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return [round(min(xs)), round(min(ys)), round(max(xs)), round(max(ys))]


def _parse_box_list(value: Any) -> list[list[int]]:
    value = _as_list(value)
    if not isinstance(value, list):
        return []
    parsed: list[list[int]] = []
    for item in value:
        box = _parse_bbox(item) or _parse_points(item)
        if box:
            parsed.append(box)
    return parsed


def _as_list(value: Any) -> Any:
    if hasattr(value, "tolist"):
        return value.tolist()
    return value


def _as_dict(value: Any) -> Any:
    if isinstance(value, dict):
        return value
    if hasattr(value, "items"):
        return dict(value.items())
    return value


def _pad_list(value: list[Any], expected_length: int, default_value: Any) -> list[Any]:
    if len(value) >= expected_length:
        return value[:expected_length]
    return value + [default_value for _ in range(expected_length - len(value))]


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
