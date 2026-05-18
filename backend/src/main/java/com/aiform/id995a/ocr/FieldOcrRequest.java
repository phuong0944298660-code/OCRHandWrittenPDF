package com.aiform.id995a.ocr;

import java.util.List;

public record FieldOcrRequest(
    String taskId,
    String filename,
    String contentType,
    byte[] fileBytes,
    String templateId,
    List<FieldOcrTemplateField> fields
) {}
