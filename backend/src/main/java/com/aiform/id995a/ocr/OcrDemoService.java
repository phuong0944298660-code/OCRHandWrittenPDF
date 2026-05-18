package com.aiform.id995a.ocr;

import com.aiform.id995a.review.EngineStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OcrDemoService {

  private static final String TEMPLATE_ID = "id988a";

  private final FieldOcrClient fieldOcrClient;
  private final Id988aTemplateRegistry templateRegistry;
  private final OcrTaskStorage taskStorage;
  private final OcrFieldQualityGate qualityGate;

  public OcrDemoService(
      FieldOcrClient fieldOcrClient,
      Id988aTemplateRegistry templateRegistry,
      OcrTaskStorage taskStorage,
      OcrFieldQualityGate qualityGate
  ) {
    this.fieldOcrClient = fieldOcrClient;
    this.templateRegistry = templateRegistry;
    this.taskStorage = taskStorage;
    this.qualityGate = qualityGate;
  }

  public OcrDemoResponse recognize(String filename, String contentType, byte[] fileBytes) throws IOException {
    OcrTaskStorage.StoredOcrTask task = taskStorage.save(filename, fileBytes);
    FieldOcrResponse fieldResponse = fieldOcrClient.recognize(new FieldOcrRequest(
        task.taskId(),
        task.filename(),
        contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
        fileBytes == null ? new byte[0] : fileBytes,
        TEMPLATE_ID,
        templateRegistry.fields().stream()
            .map(FieldOcrTemplateField::fromTemplateField)
            .toList()
    ));
    return toDemoResponse(task.filename(), fieldResponse);
  }

  private OcrDemoResponse toDemoResponse(String filename, FieldOcrResponse fieldResponse) {
    List<FieldOcrPage> fieldPages = fieldResponse.pages() == null ? List.of() : fieldResponse.pages();
    Map<Integer, FieldOcrPage> pageByNumber = new HashMap<>();
    for (FieldOcrPage page : fieldPages) {
      pageByNumber.put(page.page(), page);
    }
    Map<String, FieldOcrResult> resultByKey = new HashMap<>();
    for (FieldOcrResult result : fieldResponse.fields() == null ? List.<FieldOcrResult>of() : fieldResponse.fields()) {
      resultByKey.put(result.key(), result);
    }

    List<Id988aFieldExtraction> extractedFields = templateRegistry.fields().stream()
        .map(field -> toFieldExtraction(field, pageByNumber.get(field.page()), resultByKey.get(field.key())))
        .toList();
    Map<Integer, List<Id988aFieldExtraction>> fieldsByPage = new HashMap<>();
    for (Id988aFieldExtraction field : extractedFields) {
      fieldsByPage.computeIfAbsent(field.page(), ignored -> new ArrayList<>()).add(field);
    }

    List<OcrPage> pages = fieldPages.stream()
        .sorted(Comparator.comparingInt(FieldOcrPage::page))
        .map(page -> new OcrPage(
            page.page(),
            page.sourceImageDataUrl() == null ? "" : page.sourceImageDataUrl(),
            page.imageWidth(),
            page.imageHeight(),
            "",
            toLines(fieldsByPage.getOrDefault(page.page(), List.of())),
            List.of(),
            List.of()
        ))
        .toList();
    EngineStatus status = new EngineStatus(
        "PP-OCRv5 det/rec field ROI",
        false,
        List.of("Python OCR service returned " + extractedFields.size() + " field result(s).")
    );
    int pageCount = fieldResponse.pageCount() > 0 ? fieldResponse.pageCount() : pages.size();
    return new OcrDemoResponse(filename, "PP-OCRv5 field OCR", pageCount, pages, extractedFields, status);
  }

  private Id988aFieldExtraction toFieldExtraction(
      Id988aTemplateField field,
      FieldOcrPage page,
      FieldOcrResult result
  ) {
    String rawValue = result == null || result.value() == null ? "" : result.value();
    double confidence = result == null ? 0.0 : clamp(result.confidence());
    boolean present = result != null && (result.present() || !rawValue.isBlank());
    String value = rawValue;
    if ("checkbox".equals(field.fieldType())) {
      boolean checked = rawValue.equalsIgnoreCase("checked") || rawValue.equalsIgnoreCase("true") || present;
      value = checked ? "checked" : "unchecked";
      present = checked;
      confidence = confidence == 0.0 ? 0.70 : confidence;
    } else if ("image_presence".equals(field.fieldType()) || "signature_presence".equals(field.fieldType())) {
      if (value.isBlank()) {
        value = present ? "present" : "missing";
      }
      confidence = confidence == 0.0 ? (present ? 0.42 : 0.0) : confidence;
    }
    OcrFieldQuality quality = assessQuality(field, value, present, confidence);
    List<Integer> groupBbox = toBbox(field.groupBox(), page);
    List<Integer> valueBbox = result != null && result.bbox() != null && result.bbox().size() >= 4
        ? result.bbox()
        : toBbox(field.valueBox(), page);
    return new Id988aFieldExtraction(
        field.sectionKey(),
        field.sectionName(),
        field.page(),
        field.key(),
        field.label(),
        field.fieldType(),
        field.normalizedKey(),
        field.option(),
        value,
        present,
        confidence,
        field.groupBox(),
        field.valueBox(),
        groupBbox,
        valueBbox,
        result == null || result.source() == null || result.source().isBlank() ? "python_field_ocr" : result.source(),
        quality.status(),
        quality.reasons()
    );
  }

  private OcrFieldQuality assessQuality(Id988aTemplateField field, String value, boolean present, double confidence) {
    if (!present && (value == null || value.isBlank() || "unchecked".equalsIgnoreCase(value) || "missing".equalsIgnoreCase(value))) {
      return OcrFieldQuality.accept();
    }
    if ("checkbox".equals(field.fieldType())
        || "image_presence".equals(field.fieldType())
        || "signature_presence".equals(field.fieldType())) {
      return OcrFieldQuality.accept();
    }
    if ((value == null || value.isBlank()) && present) {
      return OcrFieldQuality.review("visible_marks_without_usable_text");
    }
    return qualityGate.assess(field, value, confidence);
  }

  private List<Integer> toBbox(TemplateRect rect, FieldOcrPage page) {
    if (page == null || page.imageWidth() <= 0 || page.imageHeight() <= 0) {
      return List.of();
    }
    int x0 = (int) Math.round(rect.x() * page.imageWidth());
    int y0 = (int) Math.round(rect.y() * page.imageHeight());
    int x1 = (int) Math.round((rect.x() + rect.width()) * page.imageWidth());
    int y1 = (int) Math.round((rect.y() + rect.height()) * page.imageHeight());
    return List.of(x0, y0, Math.max(x0, x1), Math.max(y0, y1));
  }

  private List<OcrTextLine> toLines(List<Id988aFieldExtraction> fields) {
    List<OcrTextLine> lines = new ArrayList<>();
    int lineNumber = 1;
    for (Id988aFieldExtraction field : fields) {
      if (!field.present() || field.value() == null || field.value().isBlank()) {
        continue;
      }
      lines.add(new OcrTextLine(
          lineNumber,
          List.of(
              new OcrTextSpan(field.label() + " ", false),
              new OcrTextSpan(field.value(), true)
          ),
          true
      ));
      lineNumber += 1;
    }
    return List.copyOf(lines);
  }

  private double clamp(double confidence) {
    if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, confidence));
  }
}
