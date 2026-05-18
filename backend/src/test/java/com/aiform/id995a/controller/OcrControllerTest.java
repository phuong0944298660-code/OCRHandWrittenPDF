package com.aiform.id995a.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiform.id995a.ocr.FieldOcrClient;
import com.aiform.id995a.ocr.FieldOcrPage;
import com.aiform.id995a.ocr.FieldOcrResponse;
import com.aiform.id995a.ocr.FieldOcrResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

  @TestConfiguration
  static class FakeFieldOcrConfig {
    @Bean
    @Primary
    FieldOcrClient fakeFieldOcrClient() {
      return request -> new FieldOcrResponse(
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
              "ppocr_rec"
          ))
      );
    }
  }
}
