package com.aiform.id995a.ocr;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public record FieldOcrPage(
    int page,
    @JsonAlias("image_width") @JsonProperty("image_width") int imageWidth,
    @JsonAlias("image_height") @JsonProperty("image_height") int imageHeight,
    @JsonAlias("source_image_data_url") @JsonProperty("source_image_data_url") String sourceImageDataUrl
) {}
