package com.aiform.id995a.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OcrTaskStorage {

  private final Path root;

  @Autowired
  public OcrTaskStorage(@Value("${ocr.tasks-dir:uploads/ocr-tasks}") String root) {
    this(Path.of(root));
  }

  OcrTaskStorage(Path root) {
    this.root = root;
  }

  public StoredOcrTask save(String filename, byte[] fileBytes) throws IOException {
    String taskId = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        .replace(":", "")
        .replace(".", "")
        + "-"
        + UUID.randomUUID();
    String safeFilename = safeFilename(filename);
    Path taskDir = root.resolve(taskId);
    Files.createDirectories(taskDir);
    Path storedFile = taskDir.resolve(safeFilename);
    Files.write(storedFile, fileBytes == null ? new byte[0] : fileBytes);
    return new StoredOcrTask(taskId, safeFilename, storedFile);
  }

  private String safeFilename(String filename) {
    String value = filename == null || filename.isBlank() ? "uploaded-document.pdf" : filename;
    value = value.replace('\\', '/');
    int slash = value.lastIndexOf('/');
    if (slash >= 0) {
      value = value.substring(slash + 1);
    }
    value = value.replaceAll("[^A-Za-z0-9._()\\-\\u4e00-\\u9fff]", "_");
    if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
      return "uploaded-document.pdf";
    }
    return value.toLowerCase(Locale.ROOT).endsWith(".pdf") || value.contains(".")
        ? value
        : value + ".pdf";
  }

  public record StoredOcrTask(String taskId, String filename, Path path) {}
}
