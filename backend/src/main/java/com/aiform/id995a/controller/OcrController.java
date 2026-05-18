package com.aiform.id995a.controller;

import com.aiform.id995a.ocr.OcrDemoResponse;
import com.aiform.id995a.ocr.OcrDemoService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class OcrController {

  private final OcrDemoService ocrDemoService;

  public OcrController(OcrDemoService ocrDemoService) {
    this.ocrDemoService = ocrDemoService;
  }

  @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public OcrDemoResponse recognize(@RequestPart("file") MultipartFile file) {
    try {
      return ocrDemoService.recognize(file.getOriginalFilename(), file.getContentType(), file.getBytes());
    } catch (IOException exception) {
      throw new OcrApiException("OCR service unavailable: " + trimMessage(exception.getMessage()), exception);
    }
  }

  private String trimMessage(String message) {
    if (message == null || message.isBlank()) {
      return "unknown error";
    }
    String compact = message.replace('\r', ' ').replace('\n', ' ').trim();
    return compact.length() > 600 ? compact.substring(0, 600) + "..." : compact;
  }
}
