package com.mannan.medimind;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Data model that mirrors the full structure of assets/dataset/dataset.json.
 *
 * CHANGES FROM ORIGINAL
 * ─────────────────────
 * The original MedicalDataModel.Range was missing two fields that exist in
 * every Range object in the dataset:
 *
 *   explanation_normal_en / explanation_normal_bn
 *   ──────────────────────────────────────────────
 *   A human-readable sentence shown to the patient when the value is within
 *   the normal range.  Without this, ReportAnalyzer was forced to use a
 *   generic hardcoded template ("Your X is within normal range (min-max unit)")
 *   instead of the carefully written dataset text.
 *
 * Additionally, TestData was missing the references list:
 *
 *   references  (List<Reference>)
 *   ──────────────────────────────
 *   Each TestData entry in the dataset carries a list of medical references
 *   (source / source_bn) that are displayed at the bottom of each test card
 *   in ResultFragment to give patients confidence in the information shown.
 *
 * All other fields were already correct and are preserved unchanged.
 */
public class MedicalDataModel {

    @SerializedName("tests")
    private List<TestData> tests;

    public List<TestData> getTests() { return tests; }

    // ══════════════════════════════════════════════════════════════════════
    // Inner class: TestData
    // Maps to one element of the "tests" array in dataset.json
    // ══════════════════════════════════════════════════════════════════════
    public static class TestData {

        private String name;

        @SerializedName("name_bn")
        private String nameBn;

        private List<String> aliases;

        private String unit;

        @SerializedName("description_en")
        private String descriptionEn;

        @SerializedName("description_bn")
        private String descriptionBn;

        // NEW: References list from dataset (e.g. "Mayo Clinic. CBC. 2023.")
        private List<Reference> references;

        private List<Range> ranges;

        // ── Qualitative acceptable-normal terms (NEW) ───────────────────────
        // Some qualitative tests accept more than one normal wording across
        // labs (e.g. "Nil" or "Negative" both mean normal for Protein/Sugar/
        // Ketones). This list supplements (does not replace) each Range's
        // own normal_value_string.
        @SerializedName("normal_values")
        private List<String> normalValues;

        @SerializedName("sub_tests")
        private List<SubTest> subTests;

        // Getters
        public String          getName()        { return name; }
        public String          getNameBn()      { return nameBn; }
        public List<String>    getAliases()     { return aliases; }
        public String          getUnit()        { return unit; }
        public String          getDescriptionEn() { return descriptionEn; }
        public String          getDescriptionBn() { return descriptionBn; }
        public List<Reference> getReferences()  { return references; }
        public List<Range>     getRanges()      { return ranges; }
        public List<String>    getNormalValues() { return normalValues; }
        public List<SubTest>   getSubTests()    { return subTests; }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inner class: Reference
    // Maps to one element of TestData.references[]
    // ══════════════════════════════════════════════════════════════════════
    public static class Reference {

        private String source;

        @SerializedName("source_bn")
        private String sourceBn;

        // Getters
        public String getSource()   { return source; }
        public String getSourceBn() { return sourceBn; }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inner class: Range
    // Maps to one element of TestData.ranges[]
    // ══════════════════════════════════════════════════════════════════════
    public static class Range {

        private String group;   // "Male" / "Female" / "Child" / "Infant" / "Any"

        @SerializedName("age_min")
        private int ageMin;

        @SerializedName("age_max")
        private int ageMax;

        @SerializedName("normal_min")
        private float normalMin;

        @SerializedName("normal_max")
        private float normalMax;

        // ── Qualitative comparison value (NEW) ──────────────────────────────
        // Used by tests whose normal value is text ("Nil", "Negative") or a
        // numeric span written as text ("5.0-8.0") rather than normal_min/max
        // floats — e.g. the flattened Urine Routine sub-parameters.
        @SerializedName("normal_value_string")
        private String normalValueString;

        // ── NORMAL status explanations (NEW – were missing from original) ──
        @SerializedName("explanation_normal_en")
        private String explanationNormalEn;

        @SerializedName("explanation_normal_bn")
        private String explanationNormalBn;

        // ── LOW status explanations ───────────────────────────────────────
        @SerializedName("explanation_low_en")
        private String explanationLowEn;

        @SerializedName("explanation_low_bn")
        private String explanationLowBn;

        @SerializedName("questions_for_doctor_low_en")
        private List<String> questionsForDoctorLowEn;

        @SerializedName("questions_for_doctor_low_bn")
        private List<String> questionsForDoctorLowBn;

        // ── HIGH status explanations ──────────────────────────────────────
        @SerializedName("explanation_high_en")
        private String explanationHighEn;

        @SerializedName("explanation_high_bn")
        private String explanationHighBn;

        @SerializedName("questions_for_doctor_high_en")
        private List<String> questionsForDoctorHighEn;

        @SerializedName("questions_for_doctor_high_bn")
        private List<String> questionsForDoctorHighBn;

        // Getters
        public String  getGroup()                           { return group; }
        public int     getAgeMin()                          { return ageMin; }
        public int     getAgeMax()                          { return ageMax; }
        public float   getNormalMin()                       { return normalMin; }
        public float   getNormalMax()                       { return normalMax; }
        public String  getNormalValueString()                { return normalValueString; }

        // Normal explanations (NEW)
        public String  getExplanationNormalEn()             { return explanationNormalEn; }
        public String  getExplanationNormalBn()             { return explanationNormalBn; }

        // Low / High explanations
        public String  getExplanationLowEn()                { return explanationLowEn; }
        public String  getExplanationLowBn()                { return explanationLowBn; }
        public String  getExplanationHighEn()               { return explanationHighEn; }
        public String  getExplanationHighBn()               { return explanationHighBn; }

        // Doctor questions
        public List<String> getQuestionsForDoctorLowEn()   { return questionsForDoctorLowEn; }
        public List<String> getQuestionsForDoctorLowBn()   { return questionsForDoctorLowBn; }
        public List<String> getQuestionsForDoctorHighEn()  { return questionsForDoctorHighEn; }
        public List<String> getQuestionsForDoctorHighBn()  { return questionsForDoctorHighBn; }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inner class: SubTest  (used by Urine Routine and similar panels)
    // ══════════════════════════════════════════════════════════════════════
    public static class SubTest {

        private String name;

        @SerializedName("name_bn")
        private String nameBn;

        @SerializedName("normal_value")
        private String normalValue;          // e.g. "Nil" or "0-2"

        @SerializedName("normal_value_string")
        private String normalValueString;

        private String unit;

        @SerializedName("explanation_high_en")
        private String explanationHighEn;

        @SerializedName("explanation_high_bn")
        private String explanationHighBn;

        @SerializedName("questions_for_doctor_en")
        private List<String> questionsForDoctorEn;

        @SerializedName("questions_for_doctor_bn")
        private List<String> questionsForDoctorBn;

        // Getters
        public String      getName()                  { return name; }
        public String      getNameBn()                { return nameBn; }
        public String      getNormalValue()           { return normalValue; }
        public String      getNormalValueString()     { return normalValueString; }
        public String      getUnit()                  { return unit; }
        public String      getExplanationHighEn()     { return explanationHighEn; }
        public String      getExplanationHighBn()     { return explanationHighBn; }
        public List<String> getQuestionsForDoctorEn() { return questionsForDoctorEn; }
        public List<String> getQuestionsForDoctorBn() { return questionsForDoctorBn; }
    }
}
