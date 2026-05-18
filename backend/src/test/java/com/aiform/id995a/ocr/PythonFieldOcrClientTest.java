package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PythonFieldOcrClientTest {

  @Test
  void postsFileTemplateIdAndFieldsToPythonService() throws Exception {
    AtomicReference<String> contentType = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ocr/recognize", exchange -> {
      contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
      byte[] response = """
          {
            "task_id": "task-1",
            "template_id": "id988a",
            "page_count": 1,
            "pages": [
              {"page": 1, "image_width": 1191, "image_height": 1684, "source_image_data_url": "data:image/jpeg;base64,page"}
            ],
            "fields": [
              {"key": "surnameEn.value", "value": "KUSUMA", "present": true, "confidence": 0.91, "bbox": [1,2,3,4], "source": "ppocr_rec"}
            ]
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      PythonFieldOcrClient client = new PythonFieldOcrClient(
          new ObjectMapper(),
          "http://127.0.0.1:" + server.getAddress().getPort(),
          10
      );

      FieldOcrResponse response = client.recognize(new FieldOcrRequest(
          "task-1",
          "sample.pdf",
          "application/pdf",
          "%PDF".getBytes(StandardCharsets.UTF_8),
          "id988a",
          List.of(FieldOcrTemplateField.fromTemplateField(new Id988aTemplateRegistry().fields().get(4)))
      ));

      assertThat(contentType.get()).contains("multipart/form-data");
      assertThat(requestBody.get()).contains("name=\"task_id\"");
      assertThat(requestBody.get()).contains("task-1");
      assertThat(requestBody.get()).contains("name=\"template_id\"");
      assertThat(requestBody.get()).contains("id988a");
      assertThat(requestBody.get()).contains("surnameEn.value");
      assertThat(requestBody.get()).contains("\"value_box\"");
      assertThat(requestBody.get()).contains("%PDF");
      assertThat(response.fields()).hasSize(1);
      assertThat(response.fields().get(0).value()).isEqualTo("KUSUMA");
    } finally {
      server.stop(0);
    }
  }
}
