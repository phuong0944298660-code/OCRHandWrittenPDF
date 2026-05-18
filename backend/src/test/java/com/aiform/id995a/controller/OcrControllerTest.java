package com.aiform.id995a.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiform.id995a.ocr.FieldOcrClient;
import com.aiform.id995a.ocr.FieldOcrPage;
import com.aiform.id995a.ocr.FieldOcrRequest;
import com.aiform.id995a.ocr.FieldOcrResponse;
import com.aiform.id995a.ocr.FieldOcrResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(properties = {
    "rag.enabled=false",
    "llm.enabled=false",
    "ocr.tasks-dir=target/test-ocr-tasks"
})
@AutoConfigureMockMvc
@Import(OcrControllerTest.FakeFieldOcrConfig.class)
class OcrControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ConfigurableFieldOcrClient fieldOcrClient;

  @BeforeEach
  void resetFakeClient() {
    fieldOcrClient.reset();
  }

  @Test
  void uploadsDocumentAndReturnsSplitScreenOcrPayload() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "id995a.pdf",
        "application/pdf",
        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)
    );

    mockMvc.perform(multipart("/api/ocr").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.filename", equalTo("id995a.pdf")))
        .andExpect(jsonPath("$.pageCount", equalTo(1)))
        .andExpect(jsonPath("$.model", equalTo("PP-OCRv5 field OCR")))
        .andExpect(jsonPath("$.pages[0].page", equalTo(1)))
        .andExpect(jsonPath("$.pages[0].sourceImageDataUrl", startsWith("data:image/jpeg;base64,/9j/demo-page")))
        .andExpect(jsonPath("$.pages[0].lines[0].spans[1].text", equalTo("KUSUMA")))
        .andExpect(jsonPath("$.pages[0].lines[0].spans[1].userInput", equalTo(true)))
        .andExpect(jsonPath("$.extractedFields[4].key", equalTo("surnameEn.value")))
        .andExpect(jsonPath("$.extractedFields[4].value", equalTo("KUSUMA")))
        .andExpect(jsonPath("$.extractedFields[4].extractionSource", equalTo("ppocr_rec")));
  }

  @Test
  void returnsBadGatewayWhenFieldOcrServiceFails() throws Exception {
    fieldOcrClient.failWith(new IOException(
        "Python OCR service returned HTTP 502: {\"detail\":\"PP-OCRv5 endpoint unavailable: http://127.0.0.1:8002/predict\"}"
    ));
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "id988a.pdf",
        "application/pdf",
        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)
    );

    mockMvc.perform(multipart("/api/ocr").file(file))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.status", equalTo(502)))
        .andExpect(jsonPath("$.message", startsWith("OCR service unavailable")))
        .andExpect(jsonPath("$.message", startsWith("OCR service unavailable: Python OCR service returned HTTP 502")));
  }

  @TestConfiguration
  static class FakeFieldOcrConfig {
    @Bean
    @Primary
    ConfigurableFieldOcrClient fakeFieldOcrClient() {
      return new ConfigurableFieldOcrClient();
    }
  }

  static class ConfigurableFieldOcrClient implements FieldOcrClient {
    private final AtomicReference<IOException> failure = new AtomicReference<>();

    void reset() {
      failure.set(null);
    }

    void failWith(IOException exception) {
      failure.set(exception);
    }

    @Override
    public FieldOcrResponse recognize(FieldOcrRequest request) throws IOException {
      IOException exception = failure.get();
      if (exception != null) {
        throw exception;
      }
      return new FieldOcrResponse(
          request.taskId(),
          request.templateId(),
          1,
          List.of(new FieldOcrPage(1, 1191, 1684, "data:image/jpeg;base64,/9j/demo-page")),
          List.of(new FieldOcrResult(
              "surnameEn.value",
              "KUSUMA",
              true,
              0.91,
              List.of(189, 871, 1145, 929),
              "ppocr_rec",
              "data:image/jpeg;base64,roi"
          ))
      );
    }
  }
}
