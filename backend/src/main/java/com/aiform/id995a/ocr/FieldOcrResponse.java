package com.aiform.id995a.ocr;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FieldOcrResponse(
    @JsonAlias("task_id") @JsonProperty("task_id") String taskId,
    @JsonAlias("template_id") @JsonProperty("template_id") String templateId,
    @JsonAlias("page_count") @JsonProperty("page_count") int pageCount,
    List<FieldOcrPage> pages,
    List<FieldOcrResult> fields
) {}
