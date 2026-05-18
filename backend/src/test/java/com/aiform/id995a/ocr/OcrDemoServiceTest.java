package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
              "ppocr_rec"
          ))
      );
    };
    OcrDemoService service = new OcrDemoService(
        client,
        new Id988aTemplateRegistry(),
        new OcrTaskStorage(tempDir),
        new OcrFieldQualityGate(OcrQualityLlmClient.disabled())
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
    });
  }
}
