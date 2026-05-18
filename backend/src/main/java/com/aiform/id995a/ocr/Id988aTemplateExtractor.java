package com.aiform.id995a.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Id988aTemplateExtractor {

  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
  private static final Pattern TABLE_ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
  private static final Pattern TABLE_CELL = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>");
  private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
  private static final Pattern FULL_DATE = Pattern.compile("\\b(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{2,4})\\b");
  private static final Pattern MONTH_YEAR = Pattern.compile("\\b(\\d{1,2})\\s*[/.-]\\s*(\\d{2,4})\\b");
  private static final Pattern MONTH_YEAR_TOKEN = Pattern.compile("(\\d{1,2})\\s*[/.-]\\s*(\\d{2})");
  private static final Pattern DIGIT_GROUP = Pattern.compile("\\d{1,4}");
  private static final Pattern PHONE_TEXT = Pattern.compile("[+()\\d\\s./-]{6,}");
  private static final Pattern TRAVEL_DOCUMENT_NO = Pattern.compile("[A-Z0-9]{5,15}", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOTAL_YEARS = Pattern.compile("(\\d+)\\s*(?:年|year\\(s\\)|years?)", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOTAL_MONTHS = Pattern.compile("(\\d+)\\s*(?:月|month\\(s\\)|months?)", Pattern.CASE_INSENSITIVE);
  private static final Pattern OCR_NOISE = Pattern.compile("(?i)(translation|<loc_|loc_\\d+|for official use|reference barcode)");
  private static final Map<String, List<String>> MARKDOWN_LABELS = Map.ofEntries(
      Map.entry("surnameEn", List.of("surname in english", "sumame in english")),
      Map.entry("givenNamesEn", List.of("given names in english")),
      Map.entry("maidenSurname", List.of("maiden surname")),
      Map.entry("nameChinese", List.of("name in chinese")),
      Map.entry("alias", List.of("alias")),
      Map.entry("dateOfBirth", List.of("date of birth")),
      Map.entry("placeOfBirth", List.of("place of birth")),
      Map.entry("nationality", List.of("nationality")),
      Map.entry("occupation", List.of("occupation")),
      Map.entry("travelDocument.type", List.of("travel document type")),
      Map.entry("travelDocument.no", List.of("travel document no")),
      Map.entry("travelDocument.placeOfIssue", List.of("place of issue")),
      Map.entry("travelDocument.dateOfIssue", List.of("date of issue")),
      Map.entry("travelDocument.dateOfExpiry", List.of("date of expiry")),
      Map.entry("presentAddress", List.of("present address")),
      Map.entry("domicileAddress", List.of("domicile address")),
      Map.entry("contactTelephone.no", List.of("contact telephone no")),
      Map.entry("contactTelephone.ext", List.of("ext.")),
      Map.entry("faxNo", List.of("fax no")),
      Map.entry("email", List.of("e-mail address", "email address")),
      Map.entry("currentEmployer.name", List.of("name of current employer")),
      Map.entry("currentEmployer.address", List.of("address of current employer")),
      Map.entry("undertaking.contractNo", List.of("d.h. contract no.", "d.h. contract no", "dh contract no", "contract no."))
  );

  private final Id988aTemplateRegistry templateRegistry;
  private final TemplatePageAligner pageAligner;
  private final FieldCropOcrService fieldCropOcrService;
  private final OcrFieldQualityGate qualityGate;

  @Autowired
  public Id988aTemplateExtractor(
      Id988aTemplateRegistry templateRegistry,
      TemplatePageAligner pageAligner,
      FieldCropOcrService fieldCropOcrService,
      OcrFieldQualityGate qualityGate
  ) {
    this.templateRegistry = templateRegistry;
    this.pageAligner = pageAligner;
    this.fieldCropOcrService = fieldCropOcrService;
    this.qualityGate = qualityGate;
  }

  public Id988aTemplateExtractor(Id988aTemplateRegistry templateRegistry, TemplatePageAligner pageAligner) {
    this(templateRegistry, pageAligner, FieldCropOcrService.disabled(), OcrFieldQualityGate.localOnly());
  }

  public List<Id988aFieldExtraction> extract(List<OcrPage> pages) {
    String documentText = documentText(pages);
    if (!looksLikeId988a(documentText) || looksLikeDifferentKnownForm(documentText)) {
      return List.of();
    }
    Map<Integer, OcrPage> pagesByNumber = pages.stream()
        .collect(Collectors.toMap(OcrPage::page, Function.identity(), (left, right) -> left));
    Map<Integer, BufferedImage> pageImages = new HashMap<>();
    for (OcrPage page : pages) {
      BufferedImage image = decodeImage(page.sourceImageDataUrl());
      if (image != null) {
        pageImages.put(page.page(), image);
      }
    }
    List<Id988aFieldExtraction> fields = new ArrayList<>();
    Set<Integer> expandedWorkingExperiencePages = new HashSet<>();
    for (Id988aTemplateField field : templateRegistry.fields()) {
      OcrPage page = pagesByNumber.get(field.page());
      if (page == null) {
        continue;
      }
      PageAlignment alignment = pageAligner.align(field.page(), page);
      if (isWorkingExperienceItemField(field)) {
        if (expandedWorkingExperiencePages.add(field.page())) {
          fields.addAll(extractWorkingExperienceItemFields(
              page,
              pageImages.get(field.page()),
              alignment
          ));
        }
        continue;
      }
      ImageRect groupRect = alignment.toImageRect(field.groupBox(), page.imageWidth(), page.imageHeight());
      ImageRect valueRect = alignment.toImageRect(field.valueBox(), page.imageWidth(), page.imageHeight());
      ExtractedValue value = extractValue(field, page, pageImages.get(field.page()), valueRect);
      fields.add(toFieldExtraction(field, field.key(), field.label(), field.normalizedKey(), field.groupBox(), field.valueBox(), groupRect, valueRect, value));
    }
    return List.copyOf(fields);
  }

  private List<Id988aFieldExtraction> extractWorkingExperienceItemFields(
      OcrPage page,
      BufferedImage pageImage,
      PageAlignment alignment
  ) {
    List<Id988aTemplateField> itemFields = templateRegistry.fields().stream()
        .filter(field -> field.page() == page.page())
        .filter(this::isWorkingExperienceItemField)
        .toList();
    List<WorkingExperienceRow> rows = extractWorkingExperienceRowsFromMarkdown(page.markdown(), itemFields);
    if (rows.isEmpty() || shouldPreferVisualWorkingExperienceRows(rows)) {
      List<WorkingExperienceRow> visualRows = extractWorkingExperienceRowsFromVisual(page, pageImage, alignment, itemFields);
      if (!visualRows.isEmpty()) {
        rows = visualRows;
      }
    }
    if (rows.isEmpty()) {
      return List.of();
    }

    TemplateRect rowParentBox = unionGroupBox(itemFields);
    List<Id988aFieldExtraction> extractions = new ArrayList<>();
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex += 1) {
      WorkingExperienceRow row = rows.get(rowIndex);
      TemplateRect rowGroupBox = row.slice(rowParentBox, rowIndex, rows.size());
      ImageRect rowGroupRect = alignment.toImageRect(rowGroupBox, page.imageWidth(), page.imageHeight());
      for (Id988aTemplateField field : itemFields) {
        String value = row.value(field.normalizedKey());
        TemplateRect rowValueBox = row.slice(field.valueBox(), rowIndex, rows.size());
        ImageRect rowValueRect = alignment.toImageRect(rowValueBox, page.imageWidth(), page.imageHeight());
        ExtractedValue cropValue = extractCropTextValue(field, page, pageImage, rowValueRect);
        ExtractedValue extracted = cropValue != null
            ? cropValue
            : value.isBlank()
                ? extractBlankWorkingExperienceCell(field, page, pageImage, rowValueRect)
                : finalizeTextValue(field, page, pageImage, rowValueRect, value, 0.86, "working_experience_table");
        extractions.add(toFieldExtraction(
            field,
            indexedWorkingExperienceKey(field.key(), rowIndex),
            indexedWorkingExperienceLabel(field.label(), rowIndex),
            indexedWorkingExperienceKey(field.normalizedKey(), rowIndex),
            rowGroupBox,
            rowValueBox,
            rowGroupRect,
            rowValueRect,
            extracted
        ));
      }
    }
    return List.copyOf(extractions);
  }

  private boolean shouldPreferVisualWorkingExperienceRows(List<WorkingExperienceRow> rows) {
    if (rows.isEmpty()) {
      return false;
    }
    for (WorkingExperienceRow row : rows) {
      String employer = row.value("workingExperience.items[].employerName");
      String address = row.value("workingExperience.items[].address");
      String periodFrom = row.value("workingExperience.items[].periodFrom");
      String periodTo = row.value("workingExperience.items[].periodTo");
      int employerMarkers = Pattern.compile("(?i)\\b(Mr|Mrs|Ms|Miss)\\.?\\b").matcher(employer).results().toList().size();
      int periodTokens = monthYearTokens(periodFrom + " " + periodTo).size();
      int repeatedCountries = Pattern.compile("(?i)\\b(UAE|Indonesia|Hong Kong|HK)\\b").matcher(address).results().toList().size();
      if (employerMarkers >= 2
          || periodTokens >= 3
          || repeatedCountries >= 2
          || (employer.length() > 35 && address.length() > 80)) {
        return true;
      }
    }
    return false;
  }

  private ExtractedValue extractBlankWorkingExperienceCell(
      Id988aTemplateField field,
      OcrPage page,
      BufferedImage pageImage,
      ImageRect valueRect
  ) {
    FieldCropOcrService.CropOcrResult retry = fieldCropOcrService.recognize(field, pageImage, valueRect);
    String retryText = cleanFieldText(field, retry.text());
    if (retry.attempted() && !retryText.isBlank()) {
      OcrFieldQuality retryQuality = qualityGate.assess(field, retryText, Math.max(0.55, retry.confidence()));
      if (retryQuality.accepted()) {
        return new ExtractedValue(
            retryText,
            true,
            Math.max(0.58, retry.confidence()),
            retry.source(),
            "recovered_by_retry",
            List.of("retry_source_" + retry.source())
        );
      }
    }

    ExtractedValue presence = extractPresence(page, valueRect);
    if (presence.present()) {
      return new ExtractedValue(
          "",
          true,
          0.42,
          "visual_working_experience_cell",
          "needs_ocr_text",
          List.of("visible_marks_without_usable_text")
      );
    }
    return new ExtractedValue("", false, 0.0, "working_experience_table", "empty", List.of());
  }

  private TemplateRect unionGroupBox(List<Id988aTemplateField> fields) {
    double x0 = fields.stream().map(Id988aTemplateField::groupBox).mapToDouble(TemplateRect::x).min().orElse(0);
    double y0 = fields.stream().map(Id988aTemplateField::groupBox).mapToDouble(TemplateRect::y).min().orElse(0);
    double x1 = fields.stream().map(Id988aTemplateField::groupBox).mapToDouble(rect -> rect.x() + rect.width()).max().orElse(x0);
    double y1 = fields.stream().map(Id988aTemplateField::groupBox).mapToDouble(rect -> rect.y() + rect.height()).max().orElse(y0);
    return new TemplateRect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
  }

  private Id988aFieldExtraction toFieldExtraction(
      Id988aTemplateField field,
      String key,
      String label,
      String normalizedKey,
      TemplateRect groupBox,
      TemplateRect valueBox,
      ImageRect groupRect,
      ImageRect valueRect,
      ExtractedValue value
  ) {
    return new Id988aFieldExtraction(
        field.sectionKey(),
        field.sectionName(),
        field.page(),
        key,
        label,
        field.fieldType(),
        normalizedKey,
        field.option(),
        value.value(),
        value.present(),
        value.confidence(),
        groupBox,
        valueBox,
        groupRect.toBbox(),
        valueRect.toBbox(),
        value.source(),
        value.qualityStatus(),
        value.qualityReasons(),
        "",
        !"accepted".equals(value.qualityStatus()) && !"empty".equals(value.qualityStatus())
    );
  }

  private String documentText(List<OcrPage> pages) {
    return pages.stream()
        .map(page -> page.markdown() + " " + page.blocks().stream()
            .map(OcrTextBlock::content)
            .collect(Collectors.joining(" ")))
        .collect(Collectors.joining(" "))
        .toLowerCase();
  }

  private boolean isWorkingExperienceItemField(Id988aTemplateField field) {
    return field.normalizedKey().startsWith("workingExperience.items[]");
  }

  private String indexedWorkingExperienceKey(String key, int rowIndex) {
    return key.replace("items[]", "items[" + rowIndex + "]");
  }

  private String indexedWorkingExperienceLabel(String label, int rowIndex) {
    return "第 " + (rowIndex + 1) + " 行 " + label;
  }

  private TemplateRect splitRect(TemplateRect rect, int rowIndex, int rowCount) {
    if (rowCount <= 1) {
      return rect;
    }
    double rowHeight = rect.height() / rowCount;
    return new TemplateRect(rect.x(), rect.y() + rowHeight * rowIndex, rect.width(), rowHeight);
  }

  private ImageRect splitRect(ImageRect rect, int rowIndex, int rowCount) {
    if (rowCount <= 1) {
      return rect;
    }
    int y0 = rect.y0() + (int) Math.round((double) rect.height() * rowIndex / rowCount);
    int y1 = rect.y0() + (int) Math.round((double) rect.height() * (rowIndex + 1) / rowCount);
    return new ImageRect(rect.x0(), y0, rect.x1(), Math.max(y0, y1));
  }

  private boolean looksLikeId988a(String text) {
    return text.contains("id 988a")
        || text.contains("id988a")
        || text.contains("domestic helper")
        || text.contains("外籍家庭傭工")
        || text.contains("外籍家庭佣工");
  }

  private boolean looksLikeDifferentKnownForm(String text) {
    return text.contains("application for entry for study in hong kong")
        && !text.contains("domestic helper");
  }

  private ExtractedValue extractValue(Id988aTemplateField field, OcrPage page, BufferedImage pageImage, ImageRect valueRect) {
    if ("checkbox".equals(field.fieldType())) {
      return extractCheckbox(page, valueRect);
    }
    if ("image_presence".equals(field.fieldType())) {
      return extractImagePresence(page, valueRect);
    }
    if ("signature_presence".equals(field.fieldType())) {
      ExtractedValue cropValue = extractCropTextValue(field, page, pageImage, valueRect);
      if (cropValue != null) {
        return cropValue;
      }
      String signatureText = cleanFieldText(field, extractText(page.blocks(), valueRect));
      if (!signatureText.isBlank()) {
        return finalizeTextValue(field, page, pageImage, valueRect, signatureText, 0.78, "layout_block");
      }
      return extractPresence(page, valueRect);
    }
    ExtractedValue cropValue = extractCropTextValue(field, page, pageImage, valueRect);
    if (cropValue != null) {
      return cropValue;
    }
    String text = "";
    String rawText = "";
    if (field.fieldType().startsWith("repeatable_")) {
      rawText = extractTextFromMarkdown(field, page.markdown());
      text = rawText;
    }
    if (text.isBlank()) {
      String blockText = extractText(page.blocks(), valueRect);
      if (!blockText.isBlank()) {
        rawText = blockText;
      }
      text = cleanFieldText(field, blockText);
    }
    if (text.isBlank()) {
      String markdownText = extractTextFromMarkdown(field, page.markdown());
      if (!markdownText.isBlank()) {
        rawText = markdownText;
      }
      text = cleanFieldText(field, markdownText);
    }
    if (!text.isBlank()) {
      OcrFieldQuality rawQuality = qualityGate.assess(field, rawText, 0.55);
      if (!rawText.isBlank() && !rawQuality.accepted()) {
        return finalizeRejectedTextValue(field, page, pageImage, valueRect, rawText, rawQuality, "layout_block_or_markdown");
      }
      return finalizeTextValue(field, page, pageImage, valueRect, text, 0.86, "layout_block_or_markdown");
    }
    if (rawText != null && !rawText.isBlank()) {
      if (isDateField(field)) {
        return new ExtractedValue("", false, 0.0, "layout_block_or_markdown", "rejected_garbage", List.of("non_date_raw_text_rejected"));
      }
      OcrFieldQuality rawQuality = qualityGate.assess(field, rawText, 0.35);
      if (!rawQuality.accepted()) {
        return finalizeRejectedTextValue(field, page, pageImage, valueRect, rawText, rawQuality, "layout_block_or_markdown");
      }
      return new ExtractedValue(rawText, true, 0.35, "layout_block_or_markdown", "raw_ocr_text", List.of("returning_uncleaned_raw_text"));
    }
    ExtractedValue presence = extractPresence(page, valueRect);
    if (presence.present()) {
      return new ExtractedValue("", true, 0.42, "visual_presence", "needs_ocr_text", List.of("visible_marks_without_usable_text"));
    }
    return new ExtractedValue("", false, 0.0, "none", "empty", List.of());
  }

  private ExtractedValue extractCropTextValue(
      Id988aTemplateField field,
      OcrPage page,
      BufferedImage pageImage,
      ImageRect valueRect
  ) {
    FieldCropOcrService.CropOcrResult crop = fieldCropOcrService.recognize(field, pageImage, valueRect);
    if (!crop.attempted()) {
      return null;
    }
    String cropText = cleanFieldText(field, crop.text());
    if (cropText.isBlank()) {
      return null;
    }
    OcrFieldQuality quality = qualityGate.assess(field, cropText, Math.max(0.55, crop.confidence()));
    if (!quality.accepted()) {
      return null;
    }
    return new ExtractedValue(
        cropText,
        true,
        Math.max(0.58, crop.confidence()),
        crop.source(),
        "accepted",
        List.of("field_crop_ocr")
    );
  }

  private ExtractedValue finalizeRejectedTextValue(
      Id988aTemplateField field,
      OcrPage page,
      BufferedImage pageImage,
      ImageRect valueRect,
      String rawText,
      OcrFieldQuality quality,
      String source
  ) {
    FieldCropOcrService.CropOcrResult retry = fieldCropOcrService.recognize(field, pageImage, valueRect);
    String retryText = cleanFieldText(field, retry.text());
    if (retry.attempted() && !retryText.isBlank() && !retryText.equals(rawText)) {
      OcrFieldQuality retryQuality = qualityGate.assess(field, retryText, Math.max(0.55, retry.confidence()));
      if (retryQuality.accepted()) {
        return new ExtractedValue(
            retryText,
            true,
            Math.max(0.58, retry.confidence()),
            retry.source(),
            "recovered_by_retry",
            mergeReasons(quality.reasons(), List.of("retry_source_" + retry.source()))
        );
      }
    }
    ExtractedValue presence = extractPresence(page, valueRect);
    boolean present = presence.present() || !rawText.isBlank();
    double reviewConfidence = present ? Math.min(0.42, Math.max(0.25, presence.confidence())) : 0.0;
    return new ExtractedValue("", present, reviewConfidence, source, quality.status(), quality.reasons());
  }

  private ExtractedValue finalizeTextValue(
      Id988aTemplateField field,
      OcrPage page,
      BufferedImage pageImage,
      ImageRect valueRect,
      String text,
      double confidence,
      String source
  ) {
    OcrFieldQuality quality = qualityGate.assess(field, text, confidence);
    if (quality.accepted()) {
      return new ExtractedValue(text, true, confidence, source, quality.status(), quality.reasons());
    }

    FieldCropOcrService.CropOcrResult retry = fieldCropOcrService.recognize(field, pageImage, valueRect);
    String retryText = cleanFieldText(field, retry.text());
    if (retry.attempted() && !retryText.isBlank() && !retryText.equals(text)) {
      OcrFieldQuality retryQuality = qualityGate.assess(field, retryText, Math.max(0.55, retry.confidence()));
      if (retryQuality.accepted()) {
        return new ExtractedValue(
            retryText,
            true,
            Math.max(0.58, retry.confidence()),
            retry.source(),
            "recovered_by_retry",
            mergeReasons(quality.reasons(), List.of("retry_source_" + retry.source()))
        );
      }
    }

    ExtractedValue presence = extractPresence(page, valueRect);
    boolean present = presence.present() || !text.isBlank();
    double reviewConfidence = present ? Math.min(0.42, Math.max(0.25, presence.confidence())) : 0.0;
    return new ExtractedValue("", present, reviewConfidence, source, quality.status(), quality.reasons());
  }

  private ExtractedValue extractCheckbox(OcrPage page, ImageRect valueRect) {
    OcrCheckbox best = page.checkboxes().stream()
        .filter(checkbox -> checkbox.bbox() != null && checkbox.bbox().size() >= 4)
        .filter(checkbox -> valueRect.containsCenter(checkbox.bbox(), 10))
        .max(Comparator.comparingDouble(OcrCheckbox::confidence))
        .orElse(null);
    if (best != null) {
      return new ExtractedValue(best.checked() ? "checked" : "unchecked", best.checked(), best.confidence(), "checkbox_detector", "accepted", List.of());
    }
    ExtractedValue visual = extractPresence(page, valueRect);
    if (visual.present()) {
      return new ExtractedValue("checked", true, Math.max(0.55, visual.confidence()), "visual_checkbox_presence", "accepted", List.of());
    }
    return new ExtractedValue("unchecked", false, 0.70, "checkbox_detector", "accepted", List.of());
  }

  private ExtractedValue extractImagePresence(OcrPage page, ImageRect valueRect) {
    BufferedImage image = decodeImage(page.sourceImageDataUrl());
    if (image == null || valueRect.width() == 0 || valueRect.height() == 0) {
      return new ExtractedValue("", false, 0.0, "image_presence", "empty", List.of());
    }

    int insetX = Math.max(2, (int) Math.round(valueRect.width() * 0.06));
    int insetY = Math.max(2, (int) Math.round(valueRect.height() * 0.06));
    ImageRect inner = new ImageRect(
        valueRect.x0() + insetX,
        valueRect.y0() + insetY,
        Math.max(valueRect.x0() + insetX, valueRect.x1() - insetX),
        Math.max(valueRect.y0() + insetY, valueRect.y1() - insetY)
    );
    PixelStats stats = pixelStats(image, inner);
    if (stats.sampled() == 0) {
      return new ExtractedValue("", false, 0.0, "image_presence", "empty", List.of());
    }
    double nonWhiteRatio = (double) stats.nonWhite() / stats.sampled();
    double darkRatio = (double) stats.dark() / stats.sampled();
    boolean present = nonWhiteRatio >= 0.08 || darkRatio >= 0.035;
    double confidence = Math.min(0.98, Math.max(nonWhiteRatio / 0.08, darkRatio / 0.035));
    return new ExtractedValue(present ? "present" : "missing", present, confidence, "image_presence", "accepted", List.of());
  }

  private String extractText(List<OcrTextBlock> blocks, ImageRect valueRect) {
    return blocks.stream()
        .filter(block -> block.bbox() != null && block.bbox().size() >= 4)
        .filter(block -> isTextCandidateBlock(block, valueRect))
        .sorted(Comparator
            .comparingInt((OcrTextBlock block) -> block.bbox().get(1))
            .thenComparingInt(block -> block.bbox().get(0)))
        .map(block -> cleanText(block.content()))
        .filter(text -> !text.isBlank())
        .distinct()
        .collect(Collectors.joining(" "))
        .trim();
  }

  private boolean isTextCandidateBlock(OcrTextBlock block, ImageRect valueRect) {
    ImageRect blockRect = ImageRect.fromBbox(block.bbox());
    if (blockRect.area() > Math.max(1, valueRect.area()) * 12
        && blockRect.height() > Math.max(1, valueRect.height()) * 3) {
      return false;
    }
    return valueRect.containsCenter(blockRect, 8)
        || valueRect.overlapRatioOfOther(blockRect) >= 0.45;
  }

  private String extractTextFromMarkdown(Id988aTemplateField field, String markdown) {
    String repeatableText = extractWorkingExperienceFromMarkdown(field, markdown);
    if (!repeatableText.isBlank()) {
      return repeatableText;
    }
    String workingExperienceTotal = extractWorkingExperienceTotalFromMarkdown(field, markdown);
    if (!workingExperienceTotal.isBlank()) {
      return workingExperienceTotal;
    }
    List<String> labels = MARKDOWN_LABELS.get(field.normalizedKey());
    if (labels == null || markdown == null || markdown.isBlank()) {
      return "";
    }
    List<List<String>> rows = tableRows(markdown);
    for (List<String> row : rows) {
      for (int index = 0; index < row.size(); index += 1) {
        String cell = row.get(index);
        String normalizedCell = normalizeLabel(cell);
        String matchedLabel = labels.stream()
            .filter(label -> normalizedCell.contains(normalizeLabel(label)))
            .findFirst()
            .orElse("");
        if (matchedLabel.isBlank()) {
          continue;
        }
        String directFieldValue = fieldSpecificValueFromText(field, cell);
        if (looksLikeFieldValue(directFieldValue)) {
          return directFieldValue;
        }
        String sameCellValue = valueAfterLabel(cell, matchedLabel);
        if (looksLikeFieldValue(sameCellValue)) {
          return sameCellValue;
        }
        for (int next = index + 1; next < row.size(); next += 1) {
          String value = row.get(next);
          if (looksLikeFieldValue(value) && !looksLikeFieldLabel(value)) {
            return value;
          }
        }
      }
    }
    return "";
  }

  private String fieldSpecificValueFromText(Id988aTemplateField field, String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    if ("email".equals(field.normalizedKey())) {
      java.util.regex.Matcher matcher = EMAIL.matcher(text);
      return matcher.find() ? matcher.group() : "";
    }
    if ("contactTelephone.no".equals(field.normalizedKey()) || "faxNo".equals(field.normalizedKey())) {
      java.util.regex.Matcher matcher = PHONE_TEXT.matcher(text);
      if (!matcher.find()) {
        return "";
      }
      String phone = matcher.group().replaceAll("\\s+", " ").trim();
      long digits = phone.chars().filter(Character::isDigit).count();
      return digits >= 6 ? phone : "";
    }
    if ("travelDocument.no".equals(field.normalizedKey())) {
      java.util.regex.Matcher matcher = TRAVEL_DOCUMENT_NO.matcher(text.replaceAll("\\s+", ""));
      return matcher.find() ? matcher.group().toUpperCase(java.util.Locale.ROOT) : "";
    }
    if (isDateField(field)) {
      return extractDateLikeText(text, "repeatable_date_month".equals(field.fieldType()));
    }
    return "";
  }

  private List<WorkingExperienceRow> extractWorkingExperienceRowsFromMarkdown(
      String markdown,
      List<Id988aTemplateField> itemFields
  ) {
    if (markdown == null || markdown.isBlank() || itemFields.isEmpty()) {
      return List.of();
    }
    List<List<String>> rows = tableRows(markdown);
    int headerIndex = workExperienceHeaderIndex(rows);
    if (headerIndex < 0) {
      return List.of();
    }
    boolean hasPeriodSubheader = headerIndex + 1 < rows.size()
        && normalizeLabel(String.join(" ", rows.get(headerIndex + 1))).contains("from")
        && normalizeLabel(String.join(" ", rows.get(headerIndex + 1))).contains("to");
    int dataStart = headerIndex + (hasPeriodSubheader ? 2 : 1);
    List<String> headerRow = rows.get(headerIndex);
    List<WorkingExperienceRow> items = new ArrayList<>();
    for (int index = dataStart; index < rows.size(); index += 1) {
      List<String> row = rows.get(index);
      if (isWorkExperienceSummary(row)) {
        break;
      }
      if (isWorkExperienceHeaderOrSummary(row)) {
        continue;
      }
      Map<String, String> values = new HashMap<>();
      boolean hasValue = false;
      for (Id988aTemplateField field : itemFields) {
        int column = workExperienceColumn(field.normalizedKey(), headerRow);
        String value = column < 0 ? "" : workExperienceCellValue(field, row, column);
        if (looksLikeFieldValue(value)) {
          hasValue = true;
        }
        values.put(field.normalizedKey(), value);
      }
      if (hasValue) {
        items.add(new WorkingExperienceRow(Map.copyOf(values)));
      }
    }
    return List.copyOf(items);
  }

  private List<WorkingExperienceRow> extractWorkingExperienceRowsFromVisual(
      OcrPage page,
      BufferedImage pageImage,
      PageAlignment alignment,
      List<Id988aTemplateField> itemFields
  ) {
    if (pageImage == null || itemFields.isEmpty()) {
      return List.of();
    }
    List<ImageRect> valueRects = itemFields.stream()
        .map(field -> alignment.toImageRect(field.valueBox(), page.imageWidth(), page.imageHeight()))
        .toList();
    List<ImageRect> groupRects = itemFields.stream()
        .map(field -> alignment.toImageRect(field.groupBox(), page.imageWidth(), page.imageHeight()))
        .toList();
    ImageRect unionRect = unionImageRect(groupRects);
    if (unionRect.height() <= 0 || unionRect.width() <= 0) {
      return List.of();
    }

    int[] density = new int[unionRect.height()];
    for (ImageRect rect : valueRects) {
      int startY = unionRect.y0();
      int endY = unionRect.y1();
      for (int y = startY; y < endY; y += 1) {
        int dark = 0;
        for (int x = rect.x0() + 4; x < rect.x1() - 4; x += 3) {
          if (x < 0 || x >= pageImage.getWidth() || y < 0 || y >= pageImage.getHeight()) {
            continue;
          }
          if (luminance(pageImage.getRGB(x, y)) < 175) {
            dark += 1;
          }
        }
        density[y - unionRect.y0()] += dark;
      }
    }

    int threshold = Math.max(4, valueRects.size());
    List<int[]> rawBands = new ArrayList<>();
    int bandStart = -1;
    for (int index = 0; index < density.length; index += 1) {
      if (density[index] >= threshold) {
        if (bandStart < 0) {
          bandStart = index;
        }
      } else if (bandStart >= 0) {
        rawBands.add(new int[] {bandStart, index});
        bandStart = -1;
      }
    }
    if (bandStart >= 0) {
      rawBands.add(new int[] {bandStart, density.length});
    }

    List<int[]> mergedBands = mergeRowBands(rawBands, 38, 3);
    if (mergedBands.isEmpty()) {
      return List.of();
    }

    Map<String, String> emptyValues = itemFields.stream()
        .collect(Collectors.toMap(Id988aTemplateField::normalizedKey, ignored -> ""));
    List<WorkingExperienceRow> rows = new ArrayList<>();
    for (int[] band : mergedBands) {
      double startRatio = Math.max(0.0, (double) Math.max(0, unionRect.y0() + band[0] - 10) / page.imageHeight());
      double endRatio = Math.min(1.0, (double) Math.min(page.imageHeight(), unionRect.y0() + band[1] + 10) / page.imageHeight());
      if (endRatio - startRatio < 0.01) {
        continue;
      }
      rows.add(new WorkingExperienceRow(Map.copyOf(emptyValues), startRatio, endRatio, true));
    }
    return List.copyOf(rows);
  }

  private ImageRect unionImageRect(List<ImageRect> rects) {
    int x0 = rects.stream().mapToInt(ImageRect::x0).min().orElse(0);
    int y0 = rects.stream().mapToInt(ImageRect::y0).min().orElse(0);
    int x1 = rects.stream().mapToInt(ImageRect::x1).max().orElse(x0);
    int y1 = rects.stream().mapToInt(ImageRect::y1).max().orElse(y0);
    return new ImageRect(x0, y0, Math.max(x0, x1), Math.max(y0, y1));
  }

  private List<int[]> mergeRowBands(List<int[]> rawBands, int maxGap, int minHeight) {
    List<int[]> merged = new ArrayList<>();
    for (int[] band : rawBands) {
      if (band[1] - band[0] < minHeight) {
        continue;
      }
      if (merged.isEmpty()) {
        merged.add(new int[] {band[0], band[1]});
        continue;
      }
      int[] previous = merged.get(merged.size() - 1);
      if (band[0] - previous[1] <= maxGap) {
        previous[1] = Math.max(previous[1], band[1]);
      } else {
        merged.add(new int[] {band[0], band[1]});
      }
    }
    return merged;
  }

  private String extractWorkingExperienceFromMarkdown(Id988aTemplateField field, String markdown) {
    if (markdown == null
        || markdown.isBlank()
        || !field.normalizedKey().startsWith("workingExperience.items[]")) {
      return "";
    }
    List<List<String>> rows = tableRows(markdown);
    int headerIndex = workExperienceHeaderIndex(rows);
    if (headerIndex < 0) {
      return "";
    }
    boolean hasPeriodSubheader = headerIndex + 1 < rows.size()
        && normalizeLabel(String.join(" ", rows.get(headerIndex + 1))).contains("from")
        && normalizeLabel(String.join(" ", rows.get(headerIndex + 1))).contains("to");
    int dataStart = headerIndex + (hasPeriodSubheader ? 2 : 1);
    int column = workExperienceColumn(field.normalizedKey(), rows.get(headerIndex));
    if (column < 0) {
      return "";
    }
    List<String> values = new ArrayList<>();
    for (int index = dataStart; index < rows.size(); index += 1) {
      List<String> row = rows.get(index);
      if (isWorkExperienceSummary(row)) {
        break;
      }
      if (isWorkExperienceHeaderOrSummary(row)) {
        continue;
      }
      String value = workExperienceCellValue(field, row, column);
      if (looksLikeFieldValue(value)) {
        values.add(value);
      }
    }
    return values.stream()
        .distinct()
        .collect(Collectors.joining(" | "));
  }

  private int workExperienceHeaderIndex(List<List<String>> rows) {
    for (int index = 0; index < rows.size(); index += 1) {
      String normalized = normalizeLabel(String.join(" ", rows.get(index)));
      if (normalized.contains("name of employer")
          && normalized.contains("address")
          && (normalized.contains("from") || normalized.contains("period of employment"))) {
        return index;
      }
    }
    return -1;
  }

  private int workExperienceColumn(String normalizedKey, List<String> headerRow) {
    String requestedColumn = "";
    if (normalizedKey.endsWith(".employerName")) {
      requestedColumn = "name of employer";
    } else if (normalizedKey.endsWith(".address")) {
      requestedColumn = "address";
    } else if (normalizedKey.endsWith(".periodFrom")) {
      requestedColumn = "from";
    } else if (normalizedKey.endsWith(".periodTo")) {
      requestedColumn = "to";
    }
    for (int index = 0; index < headerRow.size(); index += 1) {
      if (normalizeLabel(headerRow.get(index)).contains(requestedColumn)) {
        return index;
      }
    }
    if (headerRow.size() >= 4) {
      return switch (requestedColumn) {
        case "name of employer" -> 0;
        case "address" -> 1;
        case "from" -> 2;
        case "to" -> 3;
        default -> -1;
      };
    }
    if (headerRow.size() >= 3 && ("from".equals(requestedColumn) || "to".equals(requestedColumn))) {
      return "from".equals(requestedColumn) ? 2 : 3;
    }
    return -1;
  }

  private String workExperienceCellValue(Id988aTemplateField field, List<String> row, int column) {
    if (field.normalizedKey().endsWith(".periodFrom") || field.normalizedKey().endsWith(".periodTo")) {
      String periodText = row.size() > column
          ? row.get(column)
          : row.size() >= 3 ? row.get(2) : "";
      if (row.size() == 3 && column >= 2) {
        periodText = row.get(2);
      }
      List<String> tokens = monthYearTokens(periodText);
      if (tokens.isEmpty()) {
        return "";
      }
      return field.normalizedKey().endsWith(".periodTo")
          ? tokens.get(tokens.size() - 1)
          : tokens.get(0);
    }
    return row.size() > column ? cleanFieldText(field, row.get(column)) : "";
  }

  private String extractWorkingExperienceTotalFromMarkdown(Id988aTemplateField field, String markdown) {
    if (markdown == null
        || markdown.isBlank()
        || (!"workingExperience.totalYears".equals(field.normalizedKey())
            && !"workingExperience.totalMonths".equals(field.normalizedKey()))) {
      return "";
    }
    for (List<String> row : tableRows(markdown)) {
      if (!isWorkExperienceSummary(row)) {
        continue;
      }
      String rowText = cleanText(String.join(" ", row));
      java.util.regex.Matcher matcher = "workingExperience.totalYears".equals(field.normalizedKey())
          ? TOTAL_YEARS.matcher(rowText)
          : TOTAL_MONTHS.matcher(rowText);
      if (matcher.find()) {
        return matcher.group(1);
      }
    }
    return "";
  }

  private boolean isWorkExperienceHeaderOrSummary(List<String> row) {
    String normalized = normalizeLabel(String.join(" ", row));
    return normalized.contains("name of employer")
        || normalized.contains("period of employment")
        || (normalized.contains("from") && normalized.contains("to"))
        || isWorkExperienceSummary(row)
        || normalized.contains("please fill")
        || normalized.contains("total duration")
        || normalized.contains("year s")
        || normalized.contains("month s")
        || normalized.contains("working experience");
  }

  private boolean isWorkExperienceSummary(List<String> row) {
    return normalizeLabel(String.join(" ", row)).contains("total duration of working experience");
  }

  private List<List<String>> tableRows(String markdown) {
    java.util.regex.Matcher rowMatcher = TABLE_ROW.matcher(markdown);
    List<List<String>> rows = new ArrayList<>();
    while (rowMatcher.find()) {
      java.util.regex.Matcher cellMatcher = TABLE_CELL.matcher(rowMatcher.group(1));
      List<String> cells = new ArrayList<>();
      while (cellMatcher.find()) {
        cells.add(cleanText(cellMatcher.group(1)));
      }
      if (!cells.isEmpty()) {
        rows.add(List.copyOf(cells));
      }
    }
    return List.copyOf(rows);
  }

  private boolean looksLikeFieldValue(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String cleaned = cleanText(value);
    String lower = cleaned.toLowerCase().replaceAll("\\s+", " ").trim();
    String qualifier = lower.replaceAll("^[()\\[\\]{}\\s]+|[()\\[\\]{}\\s]+$", "");
    if (qualifier.startsWith("if ")
        || qualifier.startsWith("please ")
        || qualifier.equals("n/a")
        || qualifier.equals("na")) {
      return false;
    }
    String normalized = cleaned.replaceAll("[□☐☑✓✔\\s\\-().,/:;，。]", "");
    return !normalized.isBlank();
  }

  private boolean looksLikeFieldLabel(String value) {
    String normalized = normalizeLabel(value);
    return MARKDOWN_LABELS.values().stream()
        .flatMap(List::stream)
        .anyMatch(normalized::contains);
  }

  private String valueAfterLabel(String cell, String label) {
    String cleanCell = cleanText(cell);
    String lowerCell = cleanCell.toLowerCase();
    int labelIndex = lowerCell.indexOf(label);
    if (labelIndex < 0) {
      return "";
    }
    return cleanText(cleanCell.substring(Math.min(cleanCell.length(), labelIndex + label.length())));
  }

  private String cleanFieldText(Id988aTemplateField field, String rawText) {
    String text = cleanText(rawText);
    if (text.isBlank()) {
      return "";
    }
    text = removeInstructionText(text);
    text = removeEmbeddedLabel(field, text);
    if (OCR_NOISE.matcher(text).find()) {
      return "";
    }
    if ("email".equals(field.normalizedKey())) {
      java.util.regex.Matcher matcher = EMAIL.matcher(text);
      return matcher.find() ? matcher.group() : "";
    }
    if ("contactTelephone.no".equals(field.normalizedKey()) || "faxNo".equals(field.normalizedKey())) {
      java.util.regex.Matcher matcher = PHONE_TEXT.matcher(text);
      if (!matcher.find()) {
        return "";
      }
      String phone = matcher.group().replaceAll("\\s+", " ").trim();
      long digits = phone.chars().filter(Character::isDigit).count();
      return digits >= 6 ? phone : "";
    }
    if ("contactTelephone.ext".equals(field.normalizedKey())) {
      java.util.regex.Matcher matcher = DIGIT_GROUP.matcher(text);
      return matcher.find() ? matcher.group() : "";
    }
    if ("travelDocument.no".equals(field.normalizedKey())) {
      java.util.regex.Matcher matcher = TRAVEL_DOCUMENT_NO.matcher(text.replaceAll("\\s+", ""));
      return matcher.find() ? matcher.group().toUpperCase(java.util.Locale.ROOT) : "";
    }
    if ("number_cells".equals(field.fieldType())) {
      java.util.regex.Matcher matcher = DIGIT_GROUP.matcher(text);
      while (matcher.find()) {
        String group = matcher.group();
        if (group.length() <= 2) {
          return group;
        }
      }
      return "";
    }
    if (isDateField(field)) {
      return extractDateLikeText(text, "repeatable_date_month".equals(field.fieldType()));
    }
    if ("surnameEn".equals(field.normalizedKey()) || "givenNamesEn".equals(field.normalizedKey())) {
      return cleanEnglishNameText(text);
    }
    return text;
  }

  private String removeInstructionText(String text) {
    return text
        .replaceAll("(?i)[\\\\/|\\[\\]()\\s]*(please\\s+fill\\s+in\\s+within\\s+border).*", "")
        .replaceAll("[\\\\/|\\[\\]()\\s]*(請在界內填寫|请在界内填写).*", "")
        .trim();
  }

  private String removeEmbeddedLabel(Id988aTemplateField field, String text) {
    List<String> labels = MARKDOWN_LABELS.get(field.normalizedKey());
    if (labels == null) {
      return text;
    }
    for (String label : labels) {
      String value = valueAfterLabel(text, label);
      if (looksLikeFieldValue(value)) {
        return value;
      }
    }
    return text;
  }

  private boolean isDateField(Id988aTemplateField field) {
    return "date_cells".equals(field.fieldType())
        || "date_or_text".equals(field.fieldType())
        || "repeatable_date_month".equals(field.fieldType());
  }

  private String extractDateLikeText(String text, boolean monthYearOnly) {
    if (monthYearOnly) {
      List<String> tokens = monthYearTokens(text);
      return tokens.isEmpty() ? "" : tokens.get(0);
    }
    java.util.regex.Matcher fullDate = FULL_DATE.matcher(text);
    if (fullDate.find()) {
      return String.join("/", fullDate.group(1), fullDate.group(2), fullDate.group(3));
    }
    java.util.regex.Matcher monthYear = MONTH_YEAR.matcher(text);
    if (monthYear.find()) {
      return String.join("/", monthYear.group(1), monthYear.group(2));
    }
    List<String> groups = DIGIT_GROUP.matcher(text)
        .results()
        .map(java.util.regex.MatchResult::group)
        .toList();
    if (groups.size() >= 3 && !monthYearOnly) {
      return String.join("/", groups.subList(0, 3));
    }
    if (groups.size() >= 2) {
      return String.join("/", groups.subList(0, 2));
    }
    return "";
  }

  private List<String> monthYearTokens(String text) {
    return MONTH_YEAR_TOKEN.matcher(text)
        .results()
        .map(match -> match.group(1) + "/" + match.group(2))
        .toList();
  }

  private String cleanEnglishNameText(String text) {
    String cleaned = text
        .replaceAll("[^A-Za-z '\\-]", " ")
        .replaceAll("\\s+", " ")
        .trim();
    if (cleaned.replaceAll("[^A-Za-z]", "").length() < 2) {
      return "";
    }
    return cleaned;
  }

  private String normalizeLabel(String value) {
    return cleanText(value)
        .toLowerCase()
        .replaceAll("[^a-z0-9.]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private ExtractedValue extractPresence(OcrPage page, ImageRect valueRect) {
    BufferedImage image = decodeImage(page.sourceImageDataUrl());
    if (image == null || valueRect.width() == 0 || valueRect.height() == 0) {
      return new ExtractedValue("", false, 0.0, "visual_presence", "empty", List.of());
    }
    PixelStats stats = pixelStats(image, valueRect);
    if (stats.sampled() == 0) {
      return new ExtractedValue("", false, 0.0, "visual_presence", "empty", List.of());
    }
    double ratio = (double) stats.dark() / stats.sampled();
    boolean present = ratio >= 0.018;
    return new ExtractedValue(present ? "present" : "missing", present, Math.min(0.98, ratio / 0.018), "visual_presence", "accepted", List.of());
  }

  private PixelStats pixelStats(BufferedImage image, ImageRect valueRect) {
    int dark = 0;
    int nonWhite = 0;
    int sampled = 0;
    for (int y = valueRect.y0(); y < valueRect.y1(); y += 2) {
      for (int x = valueRect.x0(); x < valueRect.x1(); x += 2) {
        if (x < 0 || x >= image.getWidth() || y < 0 || y >= image.getHeight()) {
          continue;
        }
        sampled += 1;
        int luminance = luminance(image.getRGB(x, y));
        if (luminance < 170) {
          dark += 1;
        }
        if (luminance < 230) {
          nonWhite += 1;
        }
      }
    }
    return new PixelStats(sampled, dark, nonWhite);
  }

  private int luminance(int rgb) {
    int red = (rgb >> 16) & 0xff;
    int green = (rgb >> 8) & 0xff;
    int blue = rgb & 0xff;
    return (red * 299 + green * 587 + blue * 114) / 1000;
  }

  private String cleanText(String value) {
    if (value == null) {
      return "";
    }
    return HTML_TAG.matcher(value).replaceAll(" ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private BufferedImage decodeImage(String imageDataUrl) {
    if (imageDataUrl == null || imageDataUrl.isBlank() || imageDataUrl.startsWith("http")) {
      return null;
    }
    String base64 = imageDataUrl;
    int comma = base64.indexOf(',');
    if (comma >= 0) {
      base64 = base64.substring(comma + 1);
    }
    try {
      return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    } catch (IllegalArgumentException | IOException exception) {
      return null;
    }
  }

  private List<String> mergeReasons(List<String> left, List<String> right) {
    ArrayList<String> merged = new ArrayList<>(left);
    for (String reason : right) {
      if (!merged.contains(reason)) {
        merged.add(reason);
      }
    }
    return List.copyOf(merged);
  }

  private record ExtractedValue(
      String value,
      boolean present,
      double confidence,
      String source,
      String qualityStatus,
      List<String> qualityReasons
  ) {}

  private record WorkingExperienceRow(Map<String, String> values, Double startRatio, Double endRatio, boolean absolutePageY) {
    WorkingExperienceRow(Map<String, String> values) {
      this(values, null, null, false);
    }

    String value(String normalizedKey) {
      return values.getOrDefault(normalizedKey, "");
    }

    TemplateRect slice(TemplateRect rect, int rowIndex, int rowCount) {
      if (startRatio != null && endRatio != null && endRatio > startRatio) {
        if (absolutePageY) {
          return new TemplateRect(rect.x(), startRatio, rect.width(), endRatio - startRatio);
        }
        double y = rect.y() + rect.height() * startRatio;
        double height = rect.height() * (endRatio - startRatio);
        return new TemplateRect(rect.x(), y, rect.width(), height);
      }
      if (rowCount <= 1) {
        return rect;
      }
      double rowHeight = rect.height() / rowCount;
      return new TemplateRect(rect.x(), rect.y() + rowHeight * rowIndex, rect.width(), rowHeight);
    }
  }

  private record PixelStats(int sampled, int dark, int nonWhite) {}
}
