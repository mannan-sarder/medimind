package com.mannan.medimind;

import android.content.Context;

import com.google.gson.Gson;
import com.mannan.medimind.MedicalDataModel.Range;
import com.mannan.medimind.MedicalDataModel.TestData;

import java.util.ArrayList;
import java.util.List;

/**
 * Clinical analysis engine — converts OCR-parsed report data into fully
 * annotated {@link TestResult} objects, sourcing ALL clinical interpretation
 * (reference ranges, status, explanations, doctor questions) exclusively from
 * the local {@code dataset.json} knowledge base.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PIPELINE FOR analyze()
 * ─────────────────────────────────────────────────────────────────────────────
 *  For each parameter returned by ReportParser:
 *
 *  1. Parse the value string to float.
 *     • Numeric values proceed to step 2.
 *     • Non-numeric values (Positive, Negative, Nil, Trace, Pale Yellow...)
 *       go through buildQualitativeAnalysis(): looked up against the same
 *       dataset.json knowledge base, compared against
 *       Range.getNormalValueString() (either a text range like "5.0-8.0"
 *       or a plain word like "Nil"), and given a real NORMAL/HIGH status +
 *       explanation + doctor questions. Only falls back to a generic
 *       STATUS_UNKNOWN "cannot compare" card when the test or its
 *       qualitative range truly isn't in the knowledge base.
 *
 *  2. Fuzzy-match the test name against dataset.json (two-tier lookup):
 *     Tier 1 – exact or alias match (fast, covers ~95% of cases).
 *     Tier 2 – bidirectional substring contains (handles OCR name variations).
 *     → Not found: STATUS_UNKNOWN card (value shown, no range).
 *
 *  3. Resolve effective demographics for range selection:
 *     • OCR-extracted gender/age preferred over the user profile.
 *     • Falls back to the logged-in user's stored profile.
 *
 *  4. Select the gender/age-specific reference range from dataset.json.
 *     → No range found: STATUS_UNKNOWN card.
 *
 *  5. Determine status:
 *     value < normalMin → STATUS_LOW
 *     value > normalMax → STATUS_HIGH
 *     otherwise         → STATUS_NORMAL
 *
 *  6. Pull explanation text and doctor questions for that status from dataset.json.
 *     Substitute {{user_value}}, {{unit}}, {{normal_min}}, {{normal_max}},
 *     {{test_name}}, {{description}} placeholders.
 *
 *  7. Build and collect a TestResult with all fields populated.
 *
 *  8. Attach demographics (name, gender, age, report name) to AnalysisResult.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REQUIRED DISPLAY ORDER (enforced by ResultFragment)
 * ─────────────────────────────────────────────────────────────────────────────
 *  Header: Report Name, Patient Name, Gender, Age
 *  Per parameter: Value → Status → Full Explanation → Doctor Questions
 */
public class ReportAnalyzer {

    private final MedicalKnowledgeBase knowledgeBase;
    private final String               profileGender;
    private final int                  profileAge;
    private final String               language;
    private final Gson                 gson = new Gson();

    public ReportAnalyzer(Context context, String language) {
        this.knowledgeBase = MedicalKnowledgeBase.getInstance(context);
        this.language      = (language != null) ? language : Constants.LANG_BN;

        User user = UserManager.getInstance(context).getCurrentUser();
        this.profileGender = (user != null && user.getGender() != null)
                ? user.getGender() : Constants.GENDER_ANY;
        this.profileAge    = (user != null && user.getAge() > 0)
                ? user.getAge() : 25;
    }

    // ═════════════════════════════════════════════════════════════════════
    // AnalysisResult
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Container for the complete analysis of one medical report scan.
     * Serialised to JSON and stored in Room (MedicalReport.analysisDataJson).
     */
    public static class AnalysisResult {

        private final List<TestResult> testResults;
        private final String           summary;

        // Patient demographics — populated from OCR extraction
        private String patientName;
        private String patientGender;
        private int    patientAge = -1;

        // Report panel name — shown in ResultFragment header
        private String reportName;

        // Date actually printed on the report (collection/report date) — see
        // ReportParser.Demographics.reportDate. Null when OCR couldn't
        // confidently parse one; callers should fall back to "now" only in
        // that case, not unconditionally (see AnalyzeFragment Step D).
        private java.util.Date reportDate;

        public AnalysisResult(List<TestResult> testResults, String summary) {
            this.testResults = testResults;
            this.summary     = summary;
        }

        public List<TestResult> getTestResults()           { return testResults;   }
        public String           getSummary()               { return summary;       }

        public String           getPatientName()           { return patientName;   }
        public void             setPatientName(String n)   { this.patientName = n; }

        public String           getPatientGender()         { return patientGender; }
        public void             setPatientGender(String g) { this.patientGender = g; }

        public int              getPatientAge()            { return patientAge;    }
        public void             setPatientAge(int a)       { this.patientAge = a;  }

        public String           getReportName()            { return reportName;    }
        public void             setReportName(String r)    { this.reportName = r;  }

        public java.util.Date   getReportDate()            { return reportDate;    }
        public void             setReportDate(java.util.Date d) { this.reportDate = d; }
    }

    /** Serialise an AnalysisResult to JSON for Room storage. */
    public String         toJson(AnalysisResult r) { return gson.toJson(r); }

    /** Deserialise an AnalysisResult from Room-stored JSON. */
    public AnalysisResult fromJson(String json) {
        return gson.fromJson(json, AnalysisResult.class);
    }

    // ═════════════════════════════════════════════════════════════════════
    // PRIMARY: Offline OCR-based analysis
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Analyses parsed OCR text output from the offline {@link ReportParser}.
     *
     * Fully on-device — no network call, no API key, no remote service.
     * All clinical interpretation sourced exclusively from dataset.json.
     */
    public AnalysisResult analyze(ReportParser.ParsedReport parsedReport) {
        List<TestResult> results       = new ArrayList<>();
        int              abnormalCount = 0;

        if (parsedReport == null || parsedReport.getTestResults() == null) {
            return buildEmptyOcrResult(null);
        }

        String effectiveGender = profileGender;
        int    effectiveAge    = profileAge;

        ReportParser.Demographics ocr = parsedReport.getDemographics();
        if (ocr != null) {
            if (ocr.sex != null && !ocr.sex.isEmpty()) effectiveGender = ocr.sex;
            if (ocr.age > 0    && ocr.age < 130)       effectiveAge    = ocr.age;
        }

        for (ReportParser.ParsedTestItem item : parsedReport.getTestResults()) {
            String displayName   = safe(item.getDisplayName(), "N/A");
            String canonicalName = safe(item.getTestName(), displayName);

            if (!item.isNumeric()) {
                TestResult qr = buildQualitativeAnalysis(
                        displayName, canonicalName, item, effectiveGender, effectiveAge);
                if (Constants.STATUS_HIGH.equals(qr.getStatus())
                        || Constants.STATUS_LOW.equals(qr.getStatus())) {
                    abnormalCount++;
                }
                results.add(qr);
                continue;
            }

            float    userValue = item.getNumericValue();
            String   unit      = safe(item.getUnit(), "");
            TestData testData  = knowledgeBase.getTestData(canonicalName);
            Range    range     = knowledgeBase.getRangeForTest(
                    canonicalName, effectiveGender, effectiveAge);

            if (testData == null || range == null) {
                results.add(buildUnknownResult(displayName, userValue, unit));
                continue;
            }

            float  normalMin = range.getNormalMin();
            float  normalMax = range.getNormalMax();

            // Fallback: some flattened qualitative tests (pH, Specific Gravity,
            // microscopy counts) store their normal span as TEXT
            // ("5.0-8.0") in normal_value_string rather than as normal_min/max
            // floats. If normal_min/max are both unset (0/0) but a parseable
            // text range exists, use that instead.
            if (normalMin == 0f && normalMax == 0f
                    && range.getNormalValueString() != null
                    && !range.getNormalValueString().trim().isEmpty()) {
                float[] parsedRange = parseNumericRangeString(range.getNormalValueString());
                if (parsedRange != null) {
                    normalMin = parsedRange[0];
                    normalMax = parsedRange[1];
                }
            }

            // Unit-scale normalization: many real reports print WBC/Platelet
            // (and similar counts) in thousands ("X10^3/µL") or lakhs
            // ("Lakhs/cumm") rather than the absolute cell count our dataset
            // stores normal_min/normal_max in (e.g. 4000-11000). Comparing the
            // raw printed number (9.02) against an absolute range (4000-11000)
            // is wrong by orders of magnitude. Instead of rescaling the user's
            // value (which would make the displayed value not match what's
            // printed on their report), we rescale the COMPARISON RANGE down
            // to the same magnitude as the report's own unit, so both the
            // displayed value and displayed range stay in the report's terms.
            //
            // reportMultiplier comes from ParsedTestItem.getUnitScaleHint(),
            // computed by ReportParser directly from the raw text surrounding
            // the match — NOT from parseUnitMultiplier(unit) here. unit is
            // extractUnit()'s output, which only ever returns the dataset's
            // own literal unit string or "" (it just confirms/denies that
            // exact string is nearby); a report phrasing the same unit
            // differently ("X10^3/µL" vs the dataset's "/cumm") makes unit
            // come back empty, silently losing the scale signal entirely.
            float reportMultiplier  = item.getUnitScaleHint();
            float datasetMultiplier = parseUnitMultiplier(testData.getUnit());
            if (reportMultiplier != datasetMultiplier && datasetMultiplier > 0f) {
                float scaleFactor = reportMultiplier / datasetMultiplier;
                normalMin = normalMin / scaleFactor;
                normalMax = normalMax / scaleFactor;
            }
            android.util.Log.d("ReportAnalyzer-Scale", canonicalName
                    + " value=" + userValue
                    + " reportMultiplier=" + reportMultiplier
                    + " datasetMultiplier=" + datasetMultiplier
                    + " adjustedRange=[" + normalMin + "-" + normalMax + "]");

            String status;
            if      (userValue < normalMin) { status = Constants.STATUS_LOW;  abnormalCount++; }
            else if (userValue > normalMax) { status = Constants.STATUS_HIGH; abnormalCount++; }
            else                            { status = Constants.STATUS_NORMAL; }

            String rawExplanation;
            if (Constants.STATUS_LOW.equals(status)) {
                rawExplanation = isBn()
                        ? range.getExplanationLowBn() : range.getExplanationLowEn();
            } else if (Constants.STATUS_HIGH.equals(status)) {
                rawExplanation = isBn()
                        ? range.getExplanationHighBn() : range.getExplanationHighEn();
            } else {
                rawExplanation = isBn()
                        ? range.getExplanationNormalBn() : range.getExplanationNormalEn();
                if (rawExplanation == null || rawExplanation.trim().isEmpty()) {
                    rawExplanation = isBn()
                            ? "আপনার {{test_name}} মান ({{user_value}} {{unit}}) স্বাভাবিক সীমার মধ্যে আছে ({{normal_min}}–{{normal_max}} {{unit}})।"
                            : "Your {{test_name}} value ({{user_value}} {{unit}}) is within the normal range ({{normal_min}}–{{normal_max}} {{unit}}).";
                }
            }

            String explanation = safeSub(rawExplanation)
                    .replace("{{user_value}}", fmt(userValue))
                    .replace("{{unit}}",        unit)
                    .replace("{{test_name}}",   displayName)
                    .replace("{{normal_min}}",  fmt(normalMin))
                    .replace("{{normal_max}}",  fmt(normalMax))
                    .replace("{{description}}", isBn()
                            ? safe(testData.getDescriptionBn(), "")
                            : safe(testData.getDescriptionEn(), ""));

            List<String> questions = null;
            if (Constants.STATUS_LOW.equals(status)) {
                questions = isBn()
                        ? range.getQuestionsForDoctorLowBn()
                        : range.getQuestionsForDoctorLowEn();
            } else if (Constants.STATUS_HIGH.equals(status)) {
                questions = isBn()
                        ? range.getQuestionsForDoctorHighBn()
                        : range.getQuestionsForDoctorHighEn();
            }

            TestResult r = new TestResult();
            r.setTestName(displayName);
            r.setTestNameBn(safe(testData.getNameBn(), displayName));
            r.setValue(userValue);
            r.setValueStr(fmt(userValue));
            r.setUnit(unit);
            r.setStatus(status);
            r.setExplanation(explanation);
            r.setReferenceMin(normalMin);
            r.setReferenceMax(normalMax);
            r.setNumeric(true);
            r.setQuestionsForDoctor(questions);
            r.setReferences(testData.getReferences());
            results.add(r);
        }

        String         summary        = buildSummary(abnormalCount);
        AnalysisResult analysisResult = new AnalysisResult(results, summary);

        if (ocr != null) {
            if (ocr.patientName != null) analysisResult.setPatientName(ocr.patientName);
            if (ocr.sex         != null) analysisResult.setPatientGender(ocr.sex);
            if (ocr.age         > 0)     analysisResult.setPatientAge(ocr.age);
            if (ocr.reportName  != null) analysisResult.setReportName(ocr.reportName);
            if (ocr.reportDate  != null) analysisResult.setReportDate(ocr.reportDate);
        }

        return analysisResult;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Two-tier fuzzy knowledge-base lookup
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Locates the best matching {@link TestData} for a given test name.
     *
     * <b>Tier 1 – exact + alias match</b> (delegated to MedicalKnowledgeBase):
     *   Case-insensitive equality against the dataset key and every alias.
     *   Covers ~95% of cases when OCR produces standard terminology.
     *
     * <b>Tier 2 – bidirectional substring contains</b> (O(n) fallback):
     *   Handles OCR returning "Total White Blood Cells" when the dataset
     *   key is "WBC" (alias: "total white blood cell count"). Also handles
     *   abbreviations like "Hb" → "Hemoglobin".
     *
     * @param name Test name as returned by ReportParser (e.g. "Platelet Count").
     */
    private TestData findTestData(String name) {
        if (name == null || name.trim().isEmpty()) return null;

        // Tier 1: exact + alias (fast, handled by MedicalKnowledgeBase)
        TestData found = knowledgeBase.getTestData(name);
        if (found != null) return found;

        // Tier 2: substring contains (slower, scans all tests)
        String lowerInput = name.toLowerCase().trim();
        List<TestData> allTests = knowledgeBase.getAllTests();
        if (allTests == null) return null;

        for (TestData td : allTests) {
            String tdLower = (td.getName() != null)
                    ? td.getName().toLowerCase() : "";

            // Bidirectional: "Total White Blood Cells" ⊃ "WBC" OR vice-versa
            if (!tdLower.isEmpty()
                    && (tdLower.contains(lowerInput)
                    ||  lowerInput.contains(tdLower))) {
                return td;
            }

            // Check each alias with the same bidirectional logic
            if (td.getAliases() != null) {
                for (String alias : td.getAliases()) {
                    if (alias == null) continue;
                    String aliasLower = alias.toLowerCase();
                    if (aliasLower.contains(lowerInput)
                            || lowerInput.contains(aliasLower)) {
                        return td;
                    }
                }
            }
        }

        return null;
    }

    // ═════════════════════════════════════════════════════════════════════
    // TestResult builders
    // ═════════════════════════════════════════════════════════════════════

    /** Builds a qualitative (non-numeric) result card for values like Positive/Negative. */
    private TestResult buildQualitativeResult(String name, String valueStr, String unit) {
        TestResult r = new TestResult();
        r.setTestName(name);
        r.setValueStr(safe(valueStr, "?"));
        r.setUnit(unit);
        r.setStatus(Constants.STATUS_UNKNOWN);
        r.setNumeric(false);
        r.setExplanation(isBn()
                ? "এই ফলাফলটি সংখ্যা নয়, তাই রেফারেন্স রেঞ্জের সাথে তুলনা করা যাচ্ছে না।"
                : "This result is qualitative and cannot be compared to a reference range.");
        return r;
    }

    /**
     * Builds a real, fully-explained result card for a qualitative (non-numeric)
     * parsed item by looking up its TestData + gender/age-specific Range and
     * comparing against {@link Range#getNormalValueString()}.
     *
     * Handles two shapes of normal_value_string:
     *   • A numeric span written as text ("5.0-8.0", "1-2") — compared
     *     numerically if the OCR'd value also parses as a number.
     *   • A plain qualitative word ("Nil", "Negative", "Normal") — compared
     *     case-insensitively.
     *
     * Falls back to the generic "cannot compare" card (buildOcrTextResult)
     * when the test isn't in the knowledge base, has no qualitative range
     * for this status, or the comparison can't be performed safely.
     */
    private TestResult buildQualitativeAnalysis(String displayName, String canonicalName,
                                                 ReportParser.ParsedTestItem item,
                                                 String effectiveGender, int effectiveAge) {
        String userValueStr = safe(item.getValueStr(), "?");
        String unit         = safe(item.getUnit(), "");

        TestData testData = findTestData(canonicalName);
        Range    range     = (testData != null)
                ? knowledgeBase.getRangeForTest(canonicalName, effectiveGender, effectiveAge)
                : null;

        if (testData == null || range == null
                || range.getNormalValueString() == null
                || range.getNormalValueString().trim().isEmpty()) {
            return buildOcrTextResult(displayName, item);
        }

        String  normalStr = range.getNormalValueString().trim();

        // Build the full set of acceptable "normal" candidates: the test's
        // general normal_values list (covers lab-to-lab wording variance,
        // e.g. "Nil" vs "Negative", "Straw" vs "Pale Yellow") PLUS this
        // specific group's normal_value_string (always included, in case it
        // isn't already in the general list).
        List<String> candidates = new ArrayList<>();
        if (testData.getNormalValues() != null) candidates.addAll(testData.getNormalValues());
        if (!candidates.contains(normalStr)) candidates.add(normalStr);

        String userTrimmed = userValueStr.trim();
        Float  userNumeric  = null;
        try { userNumeric = Float.parseFloat(userTrimmed); } catch (NumberFormatException ignored) { }

        boolean isNormal   = false;
        boolean comparable = false;
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            float[] candidateRange = parseNumericRangeString(candidate);
            if (candidateRange != null) {
                if (userNumeric != null) {
                    comparable = true;
                    if (userNumeric >= candidateRange[0] && userNumeric <= candidateRange[1]) {
                        isNormal = true;
                        break;
                    }
                }
                // candidate is a numeric span but the OCR'd value isn't a
                // number — this candidate can't decide the comparison.
            } else {
                comparable = true;   // word-type candidates are always comparable
                if (userTrimmed.equalsIgnoreCase(candidate.trim())) {
                    isNormal = true;
                    break;
                }
            }
        }

        if (!comparable) {
            // Every candidate was a numeric span but the OCR'd value wasn't
            // a number — can't honestly judge normal/abnormal.
            return buildOcrTextResult(displayName, item);
        }
        String status = isNormal ? Constants.STATUS_NORMAL : Constants.STATUS_HIGH;

        // This dataset models qualitative findings as normal-vs-abnormal only
        // (one "explanation_high" per group, no separate "explanation_low") —
        // so any non-normal result, in either direction, uses that single
        // abnormal explanation rather than inventing a low/high split the
        // data doesn't support.
        String rawExplanation;
        List<String> questions = null;
        if (Constants.STATUS_NORMAL.equals(status)) {
            rawExplanation = isBn()
                    ? range.getExplanationNormalBn() : range.getExplanationNormalEn();
        } else {
            rawExplanation = isBn()
                    ? range.getExplanationHighBn() : range.getExplanationHighEn();
            questions = isBn()
                    ? range.getQuestionsForDoctorHighBn() : range.getQuestionsForDoctorHighEn();
        }

        if (rawExplanation == null || rawExplanation.trim().isEmpty()) {
            // Dataset incomplete for this status — fall back safely rather
            // than showing an empty explanation.
            return buildOcrTextResult(displayName, item);
        }

        String explanation = safeSub(rawExplanation)
                .replace("{{user_value}}", userValueStr)
                .replace("{{unit}}",       unit)
                .replace("{{test_name}}",  displayName)
                .replace("{{description}}", isBn()
                        ? safe(testData.getDescriptionBn(), "")
                        : safe(testData.getDescriptionEn(), ""));

        TestResult r = new TestResult();
        r.setTestName(displayName);
        r.setTestNameBn(safe(testData.getNameBn(), displayName));
        r.setValueStr(userValueStr);
        r.setUnit(unit);
        r.setStatus(status);
        r.setExplanation(explanation);
        r.setNumeric(false);
        r.setQuestionsForDoctor(questions);
        r.setReferences(testData.getReferences());
        return r;
    }

    /**
     * Detects the magnitude multiplier implied by a unit string — e.g.
     * "X10^3/µL", "1000/mm3", and "thousand/cumm" all mean "report value is
     * in thousands"; "Lakhs/cumm" / "lac/cumm" mean "hundred-thousands";
     * "X10^6/µL" / "million/mm3" mean "millions". Returns 1 (no scaling) for
     * plain absolute-count units or when no unit is available.
     */
    private float parseUnitMultiplier(String unit) {
        if (unit == null || unit.trim().isEmpty()) return 1f;
        String u = unit.toLowerCase();
        if (u.contains("lakh") || java.util.regex.Pattern.compile("\\blacs?\\b").matcher(u).find()) {
            return 100000f;
        }
        if (u.contains("million")
                || java.util.regex.Pattern.compile("x?\\s*10\\s*\\^?\\s*6").matcher(u).find()
                // dataset shorthand for "million" (e.g. RBC's "m/µL") — matched
                // narrowly (m immediately followed by '/') so it can't catch
                // "mm/hr", "mg/dL", "mIU/L", "mL/min..." etc.
                || java.util.regex.Pattern.compile("^m\\s*/\\s*[uµ]l").matcher(u).find()) {
            return 1000000f;
        }
        if (u.contains("thousand")
                || java.util.regex.Pattern.compile("\\b1000\\b").matcher(u).find()
                || java.util.regex.Pattern.compile("x?\\s*10\\s*\\^?\\s*3").matcher(u).find()) {
            return 1000f;
        }
        return 1f;
    }

    /**
     * Parses a "min-max" text range (e.g. "5.0-8.0", "1-2", "0.005-1.030")
     * into a two-element float array, or returns null if the string isn't
     * shaped like a numeric range (e.g. it's a plain word like "Nil").
     */
    private float[] parseNumericRangeString(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")
                .matcher(s.trim());
        if (!m.matches()) return null;
        try {
            float lo = Float.parseFloat(m.group(1));
            float hi = Float.parseFloat(m.group(2));
            return new float[]{lo, hi};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Builds a text-only result card for OCR non-numeric items. */
    private TestResult buildOcrTextResult(String name, ReportParser.ParsedTestItem item) {
        TestResult r = new TestResult();
        r.setTestName(name);
        r.setValueStr(safe(item.getValueStr(), "?"));
        r.setUnit(safe(item.getUnit(), ""));
        r.setStatus(Constants.STATUS_UNKNOWN);
        r.setNumeric(false);
        r.setExplanation(isBn()
                ? "এই ফলাফলটি সংখ্যা নয়, তাই রেফারেন্স রেঞ্জের সাথে তুলনা করা যাচ্ছে না।"
                : "This result is not numeric and cannot be compared to a reference range.");
        return r;
    }

    /** Builds a numeric result card for a parameter not found in the knowledge base. */
    private TestResult buildUnknownResult(String name, float value, String unit) {
        TestResult r = new TestResult();
        r.setTestName(name);
        r.setValue(value);
        r.setValueStr(fmt(value));
        r.setUnit(unit);
        r.setStatus(Constants.STATUS_UNKNOWN);
        r.setNumeric(true);
        r.setExplanation(isBn()
                ? "এই পরীক্ষাটি আমাদের স্থানীয় ডেটাসেটে পাওয়া যায়নি।"
                : "This test was not found in the local knowledge base.");
        return r;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Empty-result helper
    // ═════════════════════════════════════════════════════════════════════

    private AnalysisResult buildEmptyOcrResult(ReportParser.Demographics ocr) {
        String summary = isBn()
                ? "রিপোর্টে কোনো বৈধ টেস্ট ডাটা পাওয়া যায়নি।"
                : "No valid test data found in the report.";
        AnalysisResult r = new AnalysisResult(new ArrayList<>(), summary);
        if (ocr != null) {
            if (ocr.patientName != null) r.setPatientName(ocr.patientName);
            if (ocr.sex         != null) r.setPatientGender(ocr.sex);
            if (ocr.age         > 0)     r.setPatientAge(ocr.age);
            if (ocr.reportName  != null) r.setReportName(ocr.reportName);
            if (ocr.reportDate  != null) r.setReportDate(ocr.reportDate);
        }
        return r;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═════════════════════════════════════════════════════════════════════

    private String buildSummary(int abnormalCount) {
        if (abnormalCount == 0) {
            return isBn()
                    ? "সব টেস্ট স্বাভাবিক সীমার মধ্যে আছে।"
                    : "All tests are within normal range.";
        }
        return isBn()
                ? abnormalCount + "টি অস্বাভাবিক ফলাফল পাওয়া গেছে। দয়া করে ডাক্তারের পরামর্শ নিন।"
                : "Found " + abnormalCount + " abnormal result(s). Please consult a doctor.";
    }

    private boolean isBn()                     { return Constants.LANG_BN.equals(language); }
    private String  safe(String s, String def) { return (s != null && !s.isEmpty()) ? s : def; }
    private String  safeSub(String s)          { return (s != null) ? s : ""; }

    private String fmt(float v) {
        return (v == Math.floor(v) && !Float.isInfinite(v))
                ? String.valueOf((int) v)
                : String.format(java.util.Locale.getDefault(), "%.2f", v);
    }
}
