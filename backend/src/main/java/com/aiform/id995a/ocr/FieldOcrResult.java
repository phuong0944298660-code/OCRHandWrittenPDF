package com.aiform.id995a.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FieldOcrResult(
    String key,
    String value,
    boolean present,
    double confidence,
    List<Integer> bbox,
    String source,
    @JsonProperty("roi_image_data_url") String roiImageDataUrl
) {}
