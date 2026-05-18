package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PythonFieldOcrClient implements FieldOcrClient {

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String baseUrl;
  private final Duration timeout;

  public PythonFieldOcrClient(
      ObjectMapper objectMapper,
      @Value("${ocr.field-service.base-url:http://127.0.0.1:8091}") String baseUrl,
      @Value("${ocr.field-service.timeout-seconds:300}") long timeoutSeconds
  ) {
    this.objectMapper = objectMapper;
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Override
  public FieldOcrResponse recognize(FieldOcrRequest request) throws IOException {
    String boundary = "----id995a-field-ocr-" + UUID.randomUUID();
    HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/ocr/recognize"))
        .version(HttpClient.Version.HTTP_1_1)
        .timeout(timeout)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(request, boundary)))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(
          httpRequest,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException("Python OCR service returned HTTP " + response.statusCode() + ": " + response.body());
      }
      return objectMapper.readValue(response.body(), FieldOcrResponse.class);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Python OCR service request interrupted", exception);
    }
  }

  private byte[] multipartBody(FieldOcrRequest request, String boundary) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeTextPart(output, boundary, "task_id", request.taskId());
    writeTextPart(output, boundary, "template_id", request.templateId());
    writeTextPart(output, boundary, "fields", objectMapper.writeValueAsString(request.fields()));
    writeFilePart(output, boundary, request);
    write(output, "--" + boundary + "--\r\n");
    return output.toByteArray();
  }

  private void writeTextPart(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
    write(output, "--" + boundary + "\r\n");
    write(output, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
    write(output, value == null ? "" : value);
    write(output, "\r\n");
  }

  private void writeFilePart(ByteArrayOutputStream output, String boundary, FieldOcrRequest request) throws IOException {
    write(output, "--" + boundary + "\r\n");
    write(output, "Content-Disposition: form-data; name=\"file\"; filename=\"" + escapeFilename(request.filename()) + "\"\r\n");
    write(output, "Content-Type: " + (request.contentType() == null ? "application/octet-stream" : request.contentType()) + "\r\n\r\n");
    output.write(request.fileBytes() == null ? new byte[0] : request.fileBytes());
    write(output, "\r\n");
  }

  private void write(ByteArrayOutputStream output, String value) throws IOException {
    output.write(value.getBytes(StandardCharsets.UTF_8));
  }

  private String escapeFilename(String filename) {
    String value = filename == null || filename.isBlank() ? "uploaded-document.pdf" : filename;
    StringBuilder sanitized = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if ((character >= 'a' && character <= 'z')
          || (character >= 'A' && character <= 'Z')
          || (character >= '0' && character <= '9')
          || character == '.'
          || character == '-'
          || character == '_') {
        sanitized.append(character);
      } else {
        sanitized.append('_');
      }
    }
    String result = sanitized.toString();
    return result.isBlank() ? "uploaded-document.pdf" : result;
  }

  private String stripTrailingSlash(String value) {
    String normalized = value == null || value.isBlank() ? "http://127.0.0.1:8091" : value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
