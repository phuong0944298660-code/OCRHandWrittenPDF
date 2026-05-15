package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PaddleFieldCropOcrServiceTest {

  @Test
  void sendsAnnotatedCropImageToPaddleAsImageAndReturnsRecognizedText() {
    AtomicInteger fileType = new AtomicInteger(-1);
    AtomicReference<byte[]> cropBytes = new AtomicReference<>();
    PaddleOcrGateway gateway = (bytes, type) -> {
      cropBytes.set(bytes);
      fileType.set(type);
      return """
          {
            "result": {
              "layoutParsingResults": [
                {
                  "markdown": {"text": "KUSUMA"},
                  "prunedResult": {
                    "parsing_res_list": [
                      {"block_label": "text", "block_content": "KUSUMA", "block_bbox": [0, 0, 100, 20]}
                    ]
                  }
                }
              ]
            }
          }
          """;
    };
    PaddleFieldCropOcrService service = new PaddleFieldCropOcrService(gateway, new ObjectMapper(), true, 2);
    BufferedImage page = blankImage(300, 200);
    Id988aTemplateField field = new Id988aTemplateField(
        "personalParticulars",
        "Personal Particulars",
        1,
        "surnameEn.value",
        "Surname in English",
        "text_cells",
        "surnameEn",
        "",
        "[0,0,1,1]",
        "[0,0,1,1]"
    );

    FieldCropOcrService.CropOcrResult result = service.recognize(field, page, new ImageRect(50, 60, 180, 95));

    assertThat(result.attempted()).isTrue();
    assertThat(result.source()).isEqualTo("paddle_vl_crop");
    assertThat(result.text()).isEqualTo("KUSUMA");
    assertThat(fileType).hasValue(1);
    assertThat(cropBytes.get()).startsWith(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
  }

  private BufferedImage blankImage(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();
    return image;
  }
}
