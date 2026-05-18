package com.aiform.id995a.ocr;

import java.util.List;

public record FieldOcrResult(
    String key,
    String value,
    boolean present,
    double confidence,
    List<Integer> bbox,
    String source
) {}
