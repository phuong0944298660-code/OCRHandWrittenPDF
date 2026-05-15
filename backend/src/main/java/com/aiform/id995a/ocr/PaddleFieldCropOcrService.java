package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class PaddleFieldCropOcrService implements FieldCropOcrService {

  private final PaddleOcrGateway paddleOcrGateway;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final int scale;

  public PaddleFieldCropOcrService(
      PaddleOcrGateway paddleOcrGateway,
      ObjectMapper objectMapper,
      @Value("${ocr.paddle.crop-enabled:true}") boolean enabled,
      @Value("${ocr.paddle.crop-scale:3}") int scale
  ) {
    this.paddleOcrGateway = paddleOcrGateway;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.scale = Math.max(1, scale);
  }

  @Override
  public CropOcrResult recognize(Id988aTemplateField field, BufferedImage pageImage, ImageRect valueRect) {
    if (!enabled || pageImage == null || valueRect.width() <= 0 || valueRect.height() <= 0) {
      return new CropOcrResult("", 0.0, false, "paddle_vl_crop_disabled");
    }
    try {
      byte[] cropBytes = toPng(preprocess(crop(pageImage, valueRect, cropPadding(field))));
      String rawJson = paddleOcrGateway.analyze(cropBytes, 1);
      String text = extractText(rawJson);
      return new CropOcrResult(text, text.isBlank() ? 0.0 : 0.78, true, "paddle_vl_crop");
    } catch (Throwable throwable) {
      return new CropOcrResult("", 0.0, true, "paddle_vl_crop_failed");
    }
  }

  private int cropPadding(Id988aTemplateField field) {
    if ("date_cells".equals(field.fieldType()) || "number_cells".equals(field.fieldType())) {
      return 2;
    }
    return 6;
  }

  private BufferedImage crop(BufferedImage image, ImageRect rect, int pad) {
    int x0 = Math.max(0, rect.x0() - pad);
    int y0 = Math.max(0, rect.y0() - pad);
    int x1 = Math.min(image.getWidth(), rect.x1() + pad);
    int y1 = Math.min(image.getHeight(), rect.y1() + pad);
    return image.getSubimage(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
  }

  private BufferedImage preprocess(BufferedImage source) {
    int margin = Math.max(12, Math.round(Math.min(source.getWidth(), source.getHeight()) * 0.15f));
    BufferedImage canvas = new BufferedImage(
        (source.getWidth() + margin * 2) * scale,
        (source.getHeight() + margin * 2) * scale,
        BufferedImage.TYPE_INT_RGB
    );
    Graphics2D graphics = canvas.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.drawImage(
        source,
        margin * scale,
        margin * scale,
        source.getWidth() * scale,
        source.getHeight() * scale,
        null
    );
    graphics.dispose();
    return canvas;
  }

  private byte[] toPng(BufferedImage image) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  private String extractText(String rawJson) throws IOException {
    JsonNode root = objectMapper.readTree(rawJson);
    JsonNode results = root.path("result").path("layoutParsingResults");
    Set<String> textParts = new LinkedHashSet<>();
    if (results.isArray()) {
      for (JsonNode pageNode : results) {
        addText(textParts, pageNode.path("markdown").path("text").asText(""));
        JsonNode blocks = pageNode.path("prunedResult").path("parsing_res_list");
        if (blocks.isArray()) {
          for (JsonNode block : blocks) {
            addText(textParts, block.path("block_content").asText(""));
          }
        }
      }
    }
    return String.join(" ", textParts).replaceAll("\\s+", " ").trim();
  }

  private void addText(Set<String> textParts, String text) {
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    if (!normalized.isBlank()) {
      textParts.add(normalized);
    }
  }
}
