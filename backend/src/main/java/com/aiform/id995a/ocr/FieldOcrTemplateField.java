package com.aiform.id995a.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FieldOcrTemplateField(
    @JsonProperty("section_key") String sectionKey,
    @JsonProperty("section_name") String sectionName,
    int page,
    String key,
    String label,
    @JsonProperty("field_type") String fieldType,
    @JsonProperty("normalized_key") String normalizedKey,
    String option,
    @JsonProperty("group_box") TemplateRect groupBox,
    @JsonProperty("value_box") TemplateRect valueBox
) {

  public static FieldOcrTemplateField fromTemplateField(Id988aTemplateField field) {
    return new FieldOcrTemplateField(
        field.sectionKey(),
        field.sectionName(),
        field.page(),
        field.key(),
        field.label(),
        field.fieldType(),
        field.normalizedKey(),
        field.option(),
        field.groupBox(),
        field.valueBox()
    );
  }
}
