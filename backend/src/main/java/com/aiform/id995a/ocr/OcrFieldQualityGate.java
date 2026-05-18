package com.aiform.id995a.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class OcrFieldQualityGate {

  private static final Pattern LOC_TOKEN = Pattern.compile("<LOC_\\d+>|LOC_\\d+", Pattern.CASE_INSENSITIVE);
  private static final Pattern REPLACEMENT_NOISE = Pattern.compile("�|锟");
  private static final Pattern KANA_OR_HANGUL = Pattern.compile("[\\u3040-\\u30ff\\uac00-\\ud7af]");
  private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]");
  private static final Pattern LATIN_OR_DIGIT = Pattern.compile("[A-Za-z0-9]");
  private static final Pattern SYMBOL_NOISE = Pattern.compile("[{}<>`^~\\\\]");
  private static final Pattern PIPE_SEPARATOR = Pattern.compile("\\|");
  private static final Pattern DIGIT = Pattern.compile("\\d");
  private static final Pattern UNSUPPORTED_SCRIPT = Pattern.compile(
      "[\\u0370-\\u03ff\\u0400-\\u052f\\u0590-\\u05ff\\u0600-\\u06ff\\u0750-\\u077f"
          + "\\u0900-\\u097f\\u0b80-\\u0bff\\u0e00-\\u0e7f\\u10a0-\\u10ff]"
  );

  private static final List<String> FIELD_LABELS = List.of(
      "surname in english",
      "given names in english",
      "date of birth",
      "place of birth",
      "nationality",
      "occupation",
      "travel document type",
      "travel document no",
      "place of issue",
      "date of issue",
      "date of expiry",
      "present address",
      "domicile address",
      "contact telephone no",
      "fax no",
      "e-mail address",
      "email address",
      "name of current employer",
      "address of current employer",
      "name of employer",
      "period of employment",
      "extension of stay"
  );

  private final OcrQualityLlmClient llmClient;

  public OcrFieldQualityGate(OcrQualityLlmClient llmClient) {
    this.llmClient = llmClient;
  }

  public static OcrFieldQualityGate localOnly() {
    return new OcrFieldQualityGate(OcrQualityLlmClient.disabled());
  }

  public OcrFieldQuality assess(Id988aTemplateField field, String candidateText, double confidence) {
    OcrFieldQuality local = assessLocally(field, candidateText, confidence);
    if (local.accepted() || !llmClient.enabled()) {
      return local;
    }
    OcrFieldQuality remote = llmClient.inspect(field, candidateText);
    if (remote.accepted()) {
      return OcrFieldQuality.accept().withReason("llm_quality_gate_validated");
    }
    return new OcrFieldQuality(
        remote.status(),
        false,
        true,
        mergeReasons(local.reasons(), remote.reasons())
    );
  }

  OcrFieldQuality assessLocally(Id988aTemplateField field, String candidateText, double confidence) {
    String text = candidateText == null ? "" : candidateText.trim();
    if (text.isBlank()) {
      return OcrFieldQuality.accept();
    }

    List<String> reasons = new ArrayList<>();
    if (LOC_TOKEN.matcher(text).find() || REPLACEMENT_NOISE.matcher(text).find()) {
      reasons.add("locator_or_replacement_noise");
    }
    if (SYMBOL_NOISE.matcher(text).find()
        || (PIPE_SEPARATOR.matcher(text).find() && !allowsPipeSeparator(field))) {
      reasons.add("symbol_noise");
    }
    if (hasForeignFieldLabels(field, text)) {
      reasons.add("cross_field_label_leakage");
    }
    if (looksGenerated(text)) {
      reasons.add("generated_multilingual_noise");
    }
    if (hasUnsupportedScriptNoise(text)) {
      reasons.add("unsupported_script_noise");
    }
    if (hasUnexpectedCjkForLatinField(field, text)) {
      reasons.add("latin_field_contains_cjk_noise");
    }
    if (isDateField(field) && digitCount(text) == 0) {
      reasons.add("date_field_without_digits");
    }
    if (hasUnlikelyDigitDensity(field, text)) {
      reasons.add("unlikely_digit_density");
    }
    if (isPhoneLikeField(field) && digitCount(text) < 6) {
      reasons.add("phone_field_without_enough_digits");
    }
    if (isNarrowCellField(field) && text.length() > 90) {
      reasons.add("narrow_field_too_long");
    }
    if (confidence < 0.55) {
      reasons.add("low_confidence");
    }
    if (confidence < 0.5 && text.length() > 40) {
      reasons.add("long_low_confidence_text");
    }

    if (reasons.isEmpty()) {
      return OcrFieldQuality.accept();
    }
    String status = reasons.contains("cross_field_label_leakage")
        || reasons.contains("generated_multilingual_noise")
        || reasons.contains("unsupported_script_noise")
        || reasons.contains("latin_field_contains_cjk_noise")
        || reasons.contains("date_field_without_digits")
        || reasons.contains("symbol_noise")
        ? "rejected_garbage"
        : "needs_review";
    return new OcrFieldQuality(status, false, true, List.copyOf(reasons));
  }

  private boolean hasForeignFieldLabels(Id988aTemplateField field, String text) {
    String normalizedText = normalize(text);
    String fieldLabel = normalize(field.label() + " " + field.normalizedKey());
    int matches = 0;
    for (String label : FIELD_LABELS) {
      if (!normalizedText.contains(label) || fieldLabel.contains(label)) {
        continue;
      }
      matches += 1;
    }
    return matches >= 1 && text.length() > 45;
  }

  private boolean looksGenerated(String text) {
    if (text.length() < 70) {
      return false;
    }
    int latinWords = Pattern.compile("[A-Za-z]{2,}").matcher(text).results().toList().size();
    int cjk = Pattern.compile("[\\u3400-\\u9fff]").matcher(text).results().toList().size();
    int symbols = SYMBOL_NOISE.matcher(text).results().toList().size();
    boolean hasKanaOrHangul = KANA_OR_HANGUL.matcher(text).find();
    return symbols > 4 || (latinWords > 10 && cjk > 3 && hasKanaOrHangul);
  }

  private boolean hasUnsupportedScriptNoise(String text) {
    return UNSUPPORTED_SCRIPT.matcher(text).find()
        || (KANA_OR_HANGUL.matcher(text).find() && text.length() > 20);
  }

  private boolean hasUnexpectedCjkForLatinField(Id988aTemplateField field, String text) {
    if (!expectsLatinLikeValue(field) || !CJK.matcher(text).find()) {
      return false;
    }
    int latinOrDigit = (int) LATIN_OR_DIGIT.matcher(text).results().count();
    int cjk = (int) CJK.matcher(text).results().count();
    return cjk > 0 && latinOrDigit < Math.max(2, cjk);
  }

  private boolean expectsLatinLikeValue(Id988aTemplateField field) {
    if ("nameChinese".equals(field.normalizedKey())) {
      return false;
    }
    if (isDateField(field)) {
      return true;
    }
    return switch (field.fieldType()) {
      case "text", "text_cells", "date_cells", "date_or_text", "multiline_text",
          "repeatable_text", "repeatable_date_month", "number_cells" -> true;
      default -> false;
    };
  }

  private boolean hasUnlikelyDigitDensity(Id988aTemplateField field, String text) {
    String key = field.normalizedKey();
    if (isPhoneLikeField(field)
        || "travelDocument.no".equals(key)
        || "hkIdentityCard.no".equals(key)
        || isDateField(field)
        || "number_cells".equals(field.fieldType())) {
      return false;
    }
    int digits = digitCount(text);
    return digits >= 8 && digits > text.length() / 5;
  }

  private boolean isDateField(Id988aTemplateField field) {
    return "date_cells".equals(field.fieldType())
        || "date_or_text".equals(field.fieldType())
        || "repeatable_date_month".equals(field.fieldType());
  }

  private boolean isPhoneLikeField(Id988aTemplateField field) {
    String key = field.normalizedKey();
    return "contactTelephone.no".equals(key)
        || "contactTelephone.ext".equals(key)
        || "faxNo".equals(key);
  }

  private boolean isNarrowCellField(Id988aTemplateField field) {
    return "text_cells".equals(field.fieldType())
        || "date_cells".equals(field.fieldType())
        || "number_cells".equals(field.fieldType())
        || "repeatable_date_month".equals(field.fieldType());
  }

  private boolean allowsPipeSeparator(Id988aTemplateField field) {
    return field.normalizedKey().startsWith("workingExperience.items[]");
  }

  private int digitCount(String text) {
    return (int) DIGIT.matcher(text).results().count();
  }

  private String normalize(String text) {
    return String.valueOf(text)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9.]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
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
}
