package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OcrFieldQualityGateTest {

  @Test
  void asksLlmForBorderlineNoiseButDoesNotUseItToGenerateText() {
    OcrQualityLlmClient fakeLlm = new OcrQualityLlmClient() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public OcrFieldQuality inspect(Id988aTemplateField field, String candidateText) {
        assertThat(candidateText).contains("E-mail address");
        return new OcrFieldQuality("rejected_garbage", false, true, java.util.List.of("llm_crossed_field"));
      }
    };
    OcrFieldQualityGate gate = new OcrFieldQualityGate(fakeLlm);
    Id988aTemplateField field = new Id988aTemplateField(
        "personalParticulars",
        "2. Personal Particulars",
        2,
        "contactTelephone.no.value",
        "联系电话 Contact telephone no.",
        "text_cells",
        "contactTelephone.no",
        "",
        "[0,0,1,1]",
        "[0,0,1,1]"
    );

    OcrFieldQuality quality = gate.assess(
        field,
        "+ 6 2 8/1 3 1.5之间 Contact telephone no. E-mail address Name of current employer",
        0.86
    );

    assertThat(quality.accepted()).isFalse();
    assertThat(quality.status()).isEqualTo("rejected_garbage");
    assertThat(quality.reasons()).contains("llm_crossed_field");
  }

  @Test
  void rejectsUnsupportedScriptsForId988aFieldValues() {
    OcrFieldQualityGate gate = OcrFieldQualityGate.localOnly();
    Id988aTemplateField field = new Id988aTemplateField(
        "personalParticulars",
        "2. Personal Particulars",
        2,
        "domicileAddress.value",
        "Domicile address",
        "multiline_text",
        "domicileAddress",
        "",
        "[0,0,1,1]",
        "[0,0,1,1]"
    );

    OcrFieldQuality quality = gate.assess(
        field,
        "JLAsiA SiRiA xN088 RTo4-RWog,Kelurahan GменCic have,Kecmananப்ப发声ede",
        0.86
    );

    assertThat(quality.accepted()).isFalse();
    assertThat(quality.status()).isEqualTo("rejected_garbage");
    assertThat(quality.reasons()).contains("unsupported_script_noise");
  }

  @Test
  void rejectsCjkNoiseInLatinAndDateFields() {
    OcrFieldQualityGate gate = OcrFieldQualityGate.localOnly();
    Id988aTemplateField surname = new Id988aTemplateField(
        "personalParticulars",
        "2. Personal Particulars",
        1,
        "surnameEn.value",
        "Surname in English",
        "text_cells",
        "surnameEn",
        "",
        "[0,0,1,1]",
        "[0,0,1,1]"
    );
    Id988aTemplateField date = new Id988aTemplateField(
        "personalParticulars",
        "2. Personal Particulars",
        1,
        "dateOfBirth.value",
        "Date of birth",
        "date_cells",
        "dateOfBirth",
        "dd/mm/yyyy",
        "[0,0,1,1]",
        "[0,0,1,1]"
    );

    OcrFieldQuality surnameQuality = gate.assess(surname, "妧", 0.42);
    OcrFieldQuality dateQuality = gate.assess(date, "犬亏攻犬", 0.42);

    assertThat(surnameQuality.accepted()).isFalse();
    assertThat(surnameQuality.status()).isEqualTo("rejected_garbage");
    assertThat(surnameQuality.reasons()).contains("latin_field_contains_cjk_noise");
    assertThat(dateQuality.accepted()).isFalse();
    assertThat(dateQuality.status()).isEqualTo("rejected_garbage");
    assertThat(dateQuality.reasons()).contains("latin_field_contains_cjk_noise", "date_field_without_digits");
  }

  @Test
  void routesLowConfidenceHandwritingToHumanReview() {
    OcrFieldQualityGate gate = OcrFieldQualityGate.localOnly();
    Id988aTemplateField field = new Id988aTemplateField(
        "personalParticulars",
        "2. Personal Particulars",
        1,
        "occupation.value",
        "Occupation",
        "multiline_text",
        "occupation",
        "",
        "[0,0,1,1]",
        "[0,0,1,1]"
    );

    OcrFieldQuality quality = gate.assess(field, "Domestic Helper", 0.43);

    assertThat(quality.accepted()).isFalse();
    assertThat(quality.status()).isEqualTo("needs_review");
    assertThat(quality.reasons()).contains("low_confidence");
  }

  @Test
  void acceptsPipeSeparatedWorkingExperienceEntries() {
    OcrFieldQualityGate gate = OcrFieldQualityGate.localOnly();
    Id988aTemplateField field = new Id988aTemplateField(
        "workingExperience",
        "3. Working Experience",
        2,
        "workingExperience.items[].employerName.value",
        "Name of employer(s)",
        "repeatable_text",
        "workingExperience.items[].employerName",
        "",
        "[0,0,1,1]",
        "[0,0,1,1]"
    );

    OcrFieldQuality quality = gate.assess(field, "Mrs. Aisha AL-KHALI|Mr. Mohammad RASHID", 0.82);

    assertThat(quality.accepted()).isTrue();
    assertThat(quality.status()).isEqualTo("accepted");
  }
}
