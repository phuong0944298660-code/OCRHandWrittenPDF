package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OcrDemoServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void savesUploadAndSendsTemplateCoordinatesToFieldOcrService() throws Exception {
    AtomicReference<FieldOcrRequest> capturedRequest = new AtomicReference<>();
    FieldOcrClient client = request -> {
      capturedRequest.set(request);
      return new FieldOcrResponse(
          request.taskId(),
          "id988a",
          1,
          List.of(new FieldOcrPage(1, 1191, 1684, "data:image/jpeg;base64,page")),
          List.of(new FieldOcrResult(
              "surnameEn.value",
              " KUSUMA ",
              true,
              0.91,
              List.of(189, 871, 1145, 929),
              "ppocr_rec",
              "data:image/jpeg;base64,roi"
          ))
      );
    };
    OcrDemoService service = new OcrDemoService(
        client,
        new Id988aTemplateRegistry(),
        new OcrTaskStorage(tempDir),
        new OcrFieldQualityGate(OcrQualityLlmClient.disabled()),
        300
    );

    OcrDemoResponse response = service.recognize(
        "sample.pdf",
        "application/pdf",
        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)
    );

    FieldOcrRequest request = capturedRequest.get();
    assertThat(request).isNotNull();
    assertThat(request.templateId()).isEqualTo("id988a");
    assertThat(request.filename()).isEqualTo("sample.pdf");
    assertThat(request.contentType()).isEqualTo("application/pdf");
    assertThat(request.ocrParams()).isEqualTo(Map.of("render_dpi", 300));
    assertThat(request.fields()).hasSize(new Id988aTemplateRegistry().fields().size());
    assertThat(request.fields()).anySatisfy(field -> {
      assertThat(field.key()).isEqualTo("surnameEn.value");
      assertThat(field.valueBox()).isEqualTo(TemplateRect.parse("[0.1587,0.5173,0.8029,0.0346]"));
    });
    assertThat(Files.exists(tempDir.resolve(request.taskId()).resolve("sample.pdf"))).isTrue();

    assertThat(response.model()).isEqualTo("PP-OCRv5 field OCR");
    assertThat(response.pageCount()).isEqualTo(1);
    assertThat(response.pages()).hasSize(1);
    assertThat(response.extractedFields()).anySatisfy(field -> {
      assertThat(field.key()).isEqualTo("surnameEn.value");
      assertThat(field.value()).isEqualTo(" KUSUMA ");
      assertThat(field.present()).isTrue();
      assertThat(field.confidence()).isEqualTo(0.91);
      assertThat(field.extractionSource()).isEqualTo("ppocr_rec");
      assertThat(field.qualityStatus()).isEqualTo("accepted");
      assertThat(field.needsHumanReview()).isFalse();
      assertThat(field.roiImageDataUrl()).isEqualTo("data:image/jpeg;base64,roi");
    });
  }

  @Test
  void marksPageAlignmentFailuresForHumanReview() throws Exception {
    FieldOcrClient client = request -> new FieldOcrResponse(
        request.taskId(),
        "id988a",
        1,
        List.of(new FieldOcrPage(1, 1191, 1684, "data:image/jpeg;base64,page")),
        List.of(new FieldOcrResult(
            "surnameEn.value",
            "",
            true,
            0.0,
            List.of(189, 871, 1145, 929),
            "page_alignment_failed",
            "data:image/jpeg;base64,roi"
        ))
    );
    OcrDemoService service = new OcrDemoService(
        client,
        new Id988aTemplateRegistry(),
        new OcrTaskStorage(tempDir),
        new OcrFieldQualityGate(OcrQualityLlmClient.disabled()),
        300
    );

    OcrDemoResponse response = service.recognize(
        "sample.pdf",
        "application/pdf",
        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)
    );

    assertThat(response.extractedFields()).anySatisfy(field -> {
      assertThat(field.key()).isEqualTo("surnameEn.value");
      assertThat(field.extractionSource()).isEqualTo("page_alignment_failed");
      assertThat(field.qualityStatus()).isEqualTo("needs_review");
      assertThat(field.needsHumanReview()).isTrue();
    });
  }

  @Test
  void keepsUncheckedCheckboxResultUncheckedWhenPythonReturnsUncheckedText() throws Exception {
    FieldOcrClient client = request -> new FieldOcrResponse(
        request.taskId(),
        "id988a",
        1,
        List.of(new FieldOcrPage(1, 1191, 1684, "data:image/jpeg;base64,page")),
        List.of(new FieldOcrResult(
            "applicationType.contractRenewal.entryVisa.checked",
            "unchecked",
            false,
            0.86,
            List.of(865, 625, 913, 679),
            "visual_checkbox",
            "data:image/jpeg;base64,roi"
        ))
    );
    OcrDemoService service = new OcrDemoService(
        client,
        new Id988aTemplateRegistry(),
        new OcrTaskStorage(tempDir),
        new OcrFieldQualityGate(OcrQualityLlmClient.disabled()),
        300
    );

    OcrDemoResponse response = service.recognize(
        "sample.pdf",
        "application/pdf",
        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)
    );

    assertThat(response.extractedFields()).anySatisfy(field -> {
      assertThat(field.key()).isEqualTo("applicationType.contractRenewal.entryVisa.checked");
      assertThat(field.value()).isEqualTo("unchecked");
      assertThat(field.present()).isFalse();
      assertThat(field.confidence()).isEqualTo(0.86);
    });
  }
}
