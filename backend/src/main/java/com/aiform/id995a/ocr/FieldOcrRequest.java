package com.aiform.id995a.ocr;

import java.util.List;
import java.util.Map;

public record FieldOcrRequest(
    String taskId,
    String filename,
    String contentType,
    byte[] fileBytes,
    String templateId,
    List<FieldOcrTemplateField> fields,
    Map<String, Object> ocrParams
) {}
