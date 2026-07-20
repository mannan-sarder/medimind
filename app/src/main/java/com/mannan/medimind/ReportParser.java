package com.mannan.medimind;

import android.content.Context;
import android.util.Log;

import com.mannan.medimind.MedicalDataModel.SubTest;
import com.mannan.medimind.MedicalDataModel.TestData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw OCR text from a medical report into structured test items.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * ARCHITECTURE — STRICT SPATIAL + LOGICAL EXTRACTION
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * RULE 1 — LINE-BASED PAIRING
 *   A numeric value is only paired with a test keyword when it is found on the
 *   SAME line, or on the IMMEDIATELY FOLLOWING line (one-line fallback for
 *   split OCR layouts like "Hemoglobin\n14.5  g/dL  13.0–18.0").
 *   The previous global 150-char cross-line scan is removed entirely.
 *
 * RULE 2 — VALUE CONSUMPTION (no stickiness)
 *   Every numeric token extracted is recorded in a Set of consumed keys
 *   (lineIndex + charOffset + value).  Once a value is assigned to a parameter
 *   it is marked consumed and cannot be reused by any other parameter.
 *
 * RULE 3 — STRENGTHENED METADATA FILTER
 *   Expanded blocklist: PIN codes (5–7 bare digits), phone numbers, registration
 *   IDs (6+ digits no decimal), address-block lines.  isPlausibleMedicalValue()
 *   rejects any integer >= 100 000 with no decimal, and any 5–7 digit integer
 *   found on a line without a medical unit.
 *
 * RULE 4 — HEADER vs. PARAMETER DISTINCTION
 *   TestSearchEntry objects are only built for tests that have at least one
 *   reference range in the dataset (hasRanges == true) or qualitative urine
 *   sub-tests.  A dedicated REPORT_HEADER_SET of panel labels (CBC, Lipid
 *   Profile, etc.) is matched against each line; lines that are purely a header
 *   label are skipped without any value search.
 *
 * RULE 5 — EXACT KEYWORD-TO-VALUE MAPPING
 *   The suffix examined for a value starts at the character immediately after the
 *   matched keyword on that exact line.  Search entries are sorted longest-key-
 *   first so "hemoglobin" always wins over the shorter alias "hb", preventing
 *   the wrong parameter from consuming a value.
 *
 * DEMOGRAPHICS — all previous fixes preserved (shorthand gender, three-tier
 *   name extraction, labelled/title-prefix/standalone).
 */
public class ReportParser {

    private static final String TAG = "ReportParser";

    private static final int HEADER_LINE_COUNT = 5;
    private static final int HEADER_DEMO_LINES = 30;
    private static final int FOOTER_LINE_COUNT = 4;

    /**
     * Disambiguation map for tests that real reports label with the exact
     * same bare word for TWO different parameters: a Differential Count
     * percentage row ("Neutrophils 58 %") and an Absolute Count row
     * ("Neutrophils 5.23 X10^3/µL") — both literally say just "Neutrophils".
     * Both rows match the same keyword/TestSearchEntry, so whichever one is
     * encountered LAST in the report would normally be silently dropped by
     * the canonical-name dedup. When a match against one of these % tests is
     * found on a line whose unit clearly looks like a count (not "%"), the
     * match is re-targeted to the corresponding Absolute-Count test instead
     * — see the redirect logic in parse() and looksLikeAbsoluteCountUnit().
     */
    private static final Map<String, String> DIFFERENTIAL_TO_ABSOLUTE = buildDifferentialToAbsoluteMap();

    private static Map<String, String> buildDifferentialToAbsoluteMap() {
        Map<String, String> m = new HashMap<>();
        m.put("neutrophils", "Absolute Neutrophil Count (ANC)");
        m.put("lymphocytes", "Absolute Lymphocyte Count (ALC)");
        m.put("monocytes",   "Absolute Monocyte Count (AMC)");
        m.put("eosinophils", "Absolute Eosinophil Count (AEC)");
        m.put("basophils",   "Absolute Basophil Count (ABC)");
        return m;
    }

    // ── Metadata guard ────────────────────────────────────────────────────────
    private static final Pattern METADATA_KEYWORD_PATTERN = Pattern.compile(
            "\\b(uhid|patient\\s*id|lab\\s*id|id\\s*no|serial|reg(?:istration)?|page|ph\\.?\\s*no|"
            + "mob(?:ile)?|phone|date|specimen|accession|barcode|ref(?:erence)?\\s*no|receipt\\s*no|"
            + "pin|zip|post(?:al)?\\s*code|address|ward|bed\\s*no|room|"
            + "invoice|order\\s*no|bill\\s*no|bill\\s*number)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern YEAR_PATTERN  = Pattern.compile("\\b(19|20)\\d{2}\\b");
    // 5–7 digit bare integer = likely PIN / registration number
    private static final Pattern PIN_PATTERN   = Pattern.compile("^\\d{5,7}$");

    // "Referred by : Dr. X" names the REFERRING physician, never the patient —
    // but it starts with the same "Dr." title the name-fallback pattern looks
    // for, so it must be excluded explicitly rather than relying on the
    // (patient-specific) lab-brand guard above.
    private static final Pattern REFERRED_BY_PATTERN = Pattern.compile(
            "(?i)\\breferred\\s*by\\b");

    // A "Reference Intervals" cell is often printed as several wrapped lines,
    // one per threshold band — e.g. a Vitamin D row's range column prints as:
    //   Deficiency :<20
    //   Insufficiency: 20-<30
    //   Sufficiency: 30-100
    //   Toxicity:>100
    // Each of those lines starts with a plausible-looking "label" and ends
    // with a plausible-looking number, so Pass 3 (unmatched-parameter scan)
    // used to mistake them for real, separate test rows ("INSUFFICIENCY 20",
    // "SUFFICIENCY 30", "TOXICITY 100" — all stamped STATUS_UNKNOWN). These
    // band names are never themselves lab parameters, so skip them outright.
    private static final Pattern RANGE_DESCRIPTOR_LINE = Pattern.compile(
            "(?i)^(deficien(?:t|cy)|insufficien(?:t|cy)|sufficien(?:t|cy)|toxic(?:ity)?|"
            + "borderline|optimal|desirable|critical|severe|mild|moderate)\\s*"
            + "[:\\-]?\\s*[<>=]*\\s*\\d");

    // ── Medical units — used for safe-zone check ──────────────────────────────
    private static final Pattern MEDICAL_UNIT_PATTERN = Pattern.compile(
            "(?i)\\b(g/dl|gm/dl|g/l|mg/dl|mg/l|mmol/l|umol/l|nmol/l|miu/l|"
            + "iu/l|u/l|mu/l|miu/ml|ng/ml|pg/ml|ug/dl|ug/l|"
            + "cells/cumm|/cumm|cumm|10\\^3/ul|10\\^6/ul|fl|pg|"
            + "10\\^3|10\\^6|lakh|thou/ul|mm/1st\\s*hr|mm/hr|"
            + "meq/l|mmhg|sec|ratio|index|titre|titer)\\b"
            + "|(?<=\\d)\\s*%(?=\\s|$)");

    // ── Panel / section headings that are NOT extractable parameters ───────────
    private static final Set<String> REPORT_HEADER_SET = buildReportHeaderSet();

    private static Set<String> buildReportHeaderSet() {
        Set<String> s = new HashSet<>();
        for (String h : new String[]{
            "complete blood count", "cbc", "haematology", "hematology",
            "differential count", "differential leucocyte count", "dlc", "dc",
            "lipid profile", "liver function test", "lft", "kidney function test", "kft",
            "thyroid function test", "tft", "renal function test", "rft",
            "blood sugar profile", "blood chemistry", "biochemistry",
            "serum electrolytes", "coagulation profile", "urine routine examination",
            "urine r/e", "urinalysis", "iron studies", "metabolic panel",
            "haematological report", "haematological indices", "red cell indices",
            "absolute counts", "total count", "blood picture", "peripheral smear"
        }) s.add(h);
        return s;
    }

    private final MedicalKnowledgeBase knowledgeBase;

    public ReportParser(Context context) {
        this.knowledgeBase = MedicalKnowledgeBase.getInstance(context);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public data classes
    // ══════════════════════════════════════════════════════════════════════════

    public static class Demographics {
        public int    age         = -1;
        public String sex         = null;
        public String patientName = null;
        public String reportName  = null;
        // Date actually printed on the report (collection/report date), as
        // opposed to "now" — see extractReportDate(). Null when no date-like
        // field could be confidently parsed from the OCR text.
        public java.util.Date reportDate = null;
    }

    public static class ParsedTestItem {
        private final String  testName;
        private final String  displayName;
        private final String  valueStr;
        private final float   numericValue;
        private final boolean isNumeric;
        private final boolean isTextBased;
        private final String  unit;
        private final float   unitScaleHint;

        ParsedTestItem(String testName, String displayName,
                       String valueStr, String unit, boolean isTextBased) {
            this(testName, displayName, valueStr, unit, isTextBased, 1f);
        }

        ParsedTestItem(String testName, String displayName,
                       String valueStr, String unit, boolean isTextBased,
                       float unitScaleHint) {
            this.testName    = testName;
            this.displayName = (displayName != null && !displayName.isEmpty())
                               ? displayName : testName;
            this.valueStr    = valueStr;
            this.unit        = (unit != null) ? unit : "";
            this.isTextBased = isTextBased;
            this.unitScaleHint = unitScaleHint;

            float   num   = 0f;
            boolean valid = false;
            if (!isTextBased && valueStr != null) {
                try {
                    num   = Float.parseFloat(valueStr);
                    valid = true;
                } catch (NumberFormatException ignored) { }
            }
            this.numericValue = num;
            this.isNumeric    = valid;
        }

        /**
         * Magnitude multiplier (1, 1000, 100000, 1000000) detected directly
         * from the raw text surrounding this match — e.g. "X10^3/µL" or
         * "Lakhs/cumm" — REGARDLESS of whether {@link #getUnit()} found an
         * exact match against the dataset's own unit string. extractUnit()
         * only ever returns the dataset's literal unit text or "" (since it
         * just confirms/denies that exact string nearby); when a report
         * phrases the same unit differently ("X10^3/µL" vs the dataset's
         * "/cumm"), getUnit() comes back empty and silently loses this scale
         * signal. ReportAnalyzer should prefer this hint over re-deriving a
         * multiplier from getUnit() for that reason.
         */
        public float getUnitScaleHint() { return unitScaleHint; }

        public String  getTestName()     { return testName; }
        public String  getDisplayName()  { return displayName; }
        public String  getValueStr()     { return valueStr; }
        public float   getNumericValue() { return numericValue; }
        public boolean isNumeric()       { return isNumeric; }
        public boolean isTextBased()     { return isTextBased; }
        public String  getUnit()         { return unit; }
    }

    public static class ParsedReport {
        private final List<ParsedTestItem> testResults;
        private final Demographics         demographics;

        ParsedReport(List<ParsedTestItem> testResults, Demographics demographics) {
            this.testResults  = testResults;
            this.demographics = demographics;
        }

        public List<ParsedTestItem> getTestResults()  { return testResults; }
        public Demographics         getDemographics() { return demographics; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Entry point
    // ══════════════════════════════════════════════════════════════════════════

    public ParsedReport parse(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return new ParsedReport(new ArrayList<>(), new Demographics());
        }

        Demographics demographics = extractDemographics(rawText);

        // Captured from the RAW text, BEFORE cleanText() erases scientific-
        // notation unit fragments ("X10^3/µL", "X10^6/µL") for Strategy-3
        // safety. See attemptMatch() below for why this can't be re-derived
        // from the cleaned text afterwards — cleanText() deletes the exact
        // substring this needs.
        String[] rawLinesForScale = rawText.split("\n", -1);

        String   cleaned    = cleanText(rawText);
        String[] lines      = cleaned.split("\n", -1);
        int      totalLines = lines.length;

        float[]   lineUnitMultiplier = new float[totalLines];
        boolean[] lineAbsoluteUnit   = new boolean[totalLines];
        for (int i = 0; i < totalLines && i < rawLinesForScale.length; i++) {
            String rl = rawLinesForScale[i].toLowerCase();
            lineUnitMultiplier[i] = parseContextUnitMultiplier(rl);
            lineAbsoluteUnit[i]   = looksLikeAbsoluteCountUnit(rl);
        }

        List<TestData> allTests = knowledgeBase.getAllTests();
        if (allTests == null || allTests.isEmpty()) {
            return new ParsedReport(new ArrayList<>(), demographics);
        }

        Map<String, TestData> testsByName = new HashMap<>();
        for (TestData t : allTests) {
            if (t.getName() != null) testsByName.put(t.getName(), t);
        }

        // RULE 4: only build entries for tests that have reference ranges
        List<TestSearchEntry> entries = buildSearchEntries(allTests);

        // RULE 2: consumed token registry  — "lineIdx:charOffset:value"
        Set<String> consumedTokens = new HashSet<>();
        // Canonical names already assigned a result
        Set<String> processedTests = new HashSet<>();

        List<ParsedTestItem> results = new ArrayList<>();

        int bodyStart = Math.min(HEADER_LINE_COUNT, totalLines);
        int bodyEnd   = Math.max(bodyStart, totalLines - FOOTER_LINE_COUNT);

        Log.d(TAG, "Parsing " + totalLines + " lines, body=" + bodyStart + "–" + bodyEnd);

        // Which lines carry a recognisable test name of their own — used to
        // stop the next/previous-line fallbacks in attemptMatch() from
        // stealing a value that actually belongs to a NEIGHBOURING test's
        // own row (e.g. "MCHC" sitting on its own line, with "RDW-CV ..."
        // right after it — the RDW-CV value must stay RDW-CV's).
        boolean[] lineHasOwnLabel = new boolean[totalLines];
        for (int i = bodyStart; i < bodyEnd; i++) {
            String ll = lines[i].toLowerCase().trim();
            if (ll.isEmpty() || isPureMetadataLine(ll) || isReportHeaderLine(ll)) continue;
            for (TestSearchEntry entry : entries) {
                if (findWholeWord(ll, entry.searchKey) >= 0) { lineHasOwnLabel[i] = true; break; }
            }
        }

        // Which lines' text was actually consumed as the VALUE source for a
        // confirmed result above (the keyword's own line, plus whichever
        // neighbour Attempt 2/3 pulled the value from). Pass 3 below must
        // never re-claim these as a separate "unknown parameter".
        boolean[] lineUsedAsValue = new boolean[totalLines];

        // RULE 1: strictly line-by-line
        for (int lineIdx = bodyStart; lineIdx < bodyEnd; lineIdx++) {
            String line      = lines[lineIdx];
            String lineLower = line.toLowerCase().trim();
            if (lineLower.isEmpty()) continue;

            // RULE 3: skip pure metadata / address lines
            if (isPureMetadataLine(lineLower)) continue;

            // RULE 4: skip lines that are purely a report-section heading
            if (isReportHeaderLine(lineLower)) continue;

            // One-line fallback context (RULE 1)
            String prevLine      = (lineIdx - 1 >= bodyStart) ? lines[lineIdx - 1] : "";
            String prevLineLower = prevLine.toLowerCase().trim();
            String nextLine      = (lineIdx + 1 < bodyEnd) ? lines[lineIdx + 1] : "";
            String nextLineLower = nextLine.toLowerCase().trim();

            for (TestSearchEntry entry : entries) {
                // RULE 5: find keyword on this specific line
                int kwStart = findWholeWord(lineLower, entry.searchKey);
                if (kwStart < 0) continue;
                int kwEnd = kwStart + entry.searchKey.length();

                attemptMatch(entry, lineIdx, line, lineLower, kwEnd,
                             prevLine, prevLineLower, nextLine, nextLineLower,
                             lineHasOwnLabel, lineUnitMultiplier, lineAbsoluteUnit,
                             lineUsedAsValue,
                             consumedTokens, processedTests, testsByName, results);
            }
        }

        // ── Fuzzy fallback pass ──────────────────────────────────────────────
        // OCR sometimes misreads characters WITHIN a test name itself — e.g.
        // "Neutrophils" -> "Netrtts", "Monocytes" -> "Honpovtes",
        // "Eosinophils" -> "tosinoghes" — so the exact substring match above
        // never finds them at all, regardless of how well the value/unit
        // extraction works. For any test STILL unmatched after the exact
        // pass, retry every body line with an edit-distance-tolerant fuzzy
        // search instead. This runs strictly AFTER the exact pass and only
        // touches still-unmatched canonical names, so it can never override
        // or second-guess an already-confident exact match — it only
        // recovers tests that would otherwise be silently dropped.
        for (int lineIdx = bodyStart; lineIdx < bodyEnd; lineIdx++) {
            String line      = lines[lineIdx];
            String lineLower = line.toLowerCase().trim();
            if (lineLower.isEmpty()) continue;
            if (isPureMetadataLine(lineLower)) continue;
            if (isReportHeaderLine(lineLower)) continue;

            String prevLine      = (lineIdx - 1 >= bodyStart) ? lines[lineIdx - 1] : "";
            String prevLineLower = prevLine.toLowerCase().trim();
            String nextLine      = (lineIdx + 1 < bodyEnd) ? lines[lineIdx + 1] : "";
            String nextLineLower = nextLine.toLowerCase().trim();

            for (TestSearchEntry entry : entries) {
                if (processedTests.contains(entry.canonicalName)) continue;

                int[] fuzzy = findFuzzyWord(lineLower, entry.searchKey);
                if (fuzzy == null) continue;
                int kwEnd = fuzzy[0] + fuzzy[1];

                attemptMatch(entry, lineIdx, line, lineLower, kwEnd,
                             prevLine, prevLineLower, nextLine, nextLineLower,
                             lineHasOwnLabel, lineUnitMultiplier, lineAbsoluteUnit,
                             lineUsedAsValue,
                             consumedTokens, processedTests, testsByName, results);
            }
        }

        // ── Pass 3: unmatched rows — parameter present on the report but NOT
        // in our knowledge base ─────────────────────────────────────────────
        // The two passes above only ever look for keywords from tests that
        // EXIST in dataset.json (RULE 4), so a row for a real parameter the
        // dataset simply doesn't carry (an unusual or hospital-specific test)
        // is invisible to them and silently vanishes. Instead of dropping it,
        // scan every still-unclaimed body line for a short label-like token
        // followed by a plausible number (same line, or the line right
        // before/after it, mirroring Attempts 1–3 above) and emit it as a
        // bare ParsedTestItem under that literal label. ReportAnalyzer
        // already has a ready-made path for this: knowledgeBase.getTestData()
        // returns null for a name with no dataset entry, and analyze() turns
        // that into a STATUS_UNKNOWN card — "এই পরীক্ষাটি আমাদের স্থানীয়
        // ডেটাসেটে পাওয়া যায়নি" — showing the name and value with no
        // fabricated range. No changes needed there.
        //
        // Guards against false positives (junk text, addresses, the
        // analyzer/instrument description line, signatures):
        //  • skipped entirely if this exact line already fed a KNOWN result
        //    (lineUsedAsValue) or carries a known test's own keyword
        //    (lineHasOwnLabel) — Pass 3 never second-guesses Passes 1–2.
        //  • isPureMetadataLine / isReportHeaderLine — same admin/section
        //    filter used everywhere else in this file.
        //  • candidate line capped at 60 chars — long descriptive sentences
        //    (the "Estimations are carried out by SYSMEX..." analyzer line,
        //    doctor signatures) never qualify; real parameter rows are short.
        //  • extractValue()'s own range-bound exclusion (Strategy 6) already
        //    refuses a bare "40-80"-style range with no separable value, so
        //    OCR debris like "Do   02-10" (garbled "00") never produces a
        //    fake reading.
        //  • isPlausibleMedicalValue() — same numeric sanity gate Passes 1–2
        //    use (blocks ID/phone-sized integers etc).
        final int MAX_UNKNOWN_LINE_LEN = 60;
        Pattern unknownLabelPattern = Pattern.compile("^([a-z][a-z/().,%-]{1,25})\\b");

        for (int lineIdx = bodyStart; lineIdx < bodyEnd; lineIdx++) {
            if (lineUsedAsValue[lineIdx] || lineHasOwnLabel[lineIdx]) continue;

            String line      = lines[lineIdx].trim();
            String lineLower = line.toLowerCase();
            if (line.isEmpty() || line.length() > MAX_UNKNOWN_LINE_LEN) continue;
            if (isPureMetadataLine(lineLower)) continue;
            if (isReportHeaderLine(lineLower)) continue;
            if (RANGE_DESCRIPTOR_LINE.matcher(lineLower).find()) continue;

            Matcher lm = unknownLabelPattern.matcher(lineLower);
            if (!lm.find()) continue;
            String label = lm.group(1).trim();
            if (label.replaceAll("[^a-z]", "").length() < 2) continue;
            if (processedTests.contains(label)) continue;

            TestSearchEntry dummy =
                    new TestSearchEntry(label, label, label, "", false, false);

            String suffix      = line.substring(Math.min(lm.end(), line.length())).trim();
            ValueMatch vm = suffix.isEmpty() ? null
                    : extractValue(suffix, suffix.toLowerCase(), dummy, lineIdx, lm.end(),
                                   consumedTokens);
            int valueLineIdx = lineIdx;

            // Check the PREVIOUS line before the next one: the label-only
            // rows actually seen in practice (PCT/PDW under the PLT panel,
            // mirroring MCHC) print their value on the row ABOVE the label,
            // not below it. Checking previous-first also stops THIS label
            // from reaching past a genuinely separate label-only row right
            // after it and stealing ITS value (e.g. "PCT" must not grab
            // PDW's "13.10 fl 10-18" just because it's the next line down).
            if (vm == null && lineIdx - 1 >= bodyStart
                    && !lineUsedAsValue[lineIdx - 1] && !lineHasOwnLabel[lineIdx - 1]) {
                String pl = lines[lineIdx - 1].trim();
                if (!pl.isEmpty() && pl.length() <= MAX_UNKNOWN_LINE_LEN) {
                    String pll = pl.toLowerCase();
                    if (!isPureMetadataLine(pll) && !isReportHeaderLine(pll)) {
                        vm = extractValue(pl, pll, dummy, lineIdx - 1, 0, consumedTokens);
                        if (vm != null) valueLineIdx = lineIdx - 1;
                    }
                }
            }
            if (vm == null && lineIdx + 1 < bodyEnd
                    && !lineUsedAsValue[lineIdx + 1] && !lineHasOwnLabel[lineIdx + 1]) {
                String nl = lines[lineIdx + 1].trim();
                if (!nl.isEmpty() && nl.length() <= MAX_UNKNOWN_LINE_LEN) {
                    String nll = nl.toLowerCase();
                    if (!isPureMetadataLine(nll) && !isReportHeaderLine(nll)) {
                        vm = extractValue(nl, nll, dummy, lineIdx + 1, 0, consumedTokens);
                        if (vm != null) valueLineIdx = lineIdx + 1;
                    }
                }
            }

            if (vm == null) continue;
            if (!isPlausibleMedicalValue(vm.value, lineLower)) continue;

            consumedTokens.add(vm.consumeKey);
            processedTests.add(label);
            lineUsedAsValue[lineIdx] = true;
            if (valueLineIdx >= 0 && valueLineIdx < lineUsedAsValue.length) {
                lineUsedAsValue[valueLineIdx] = true;
            }

            String displayLabel = label.toUpperCase();
            results.add(new ParsedTestItem(displayLabel, displayLabel, vm.value, "", false, 1f));
            Log.d(TAG, "Unmatched parameter [" + displayLabel + "] = " + vm.value
                    + " @ line " + lineIdx + " (not in knowledge base)");
        }

        Log.d(TAG, "Extracted " + results.size() + " results.");
        return new ParsedReport(results, demographics);
    }

    /**
     * Given that {@code entry}'s keyword was found ending at {@code kwEnd} on
     * {@code line}, runs the full extract → disambiguate → dedup → record
     * pipeline. Shared by both the exact-match pass and the fuzzy fallback
     * pass in parse() so the two stay behaviourally identical apart from how
     * the keyword position itself was located.
     */
    private void attemptMatch(TestSearchEntry entry, int lineIdx, String line, String lineLower,
                               int kwEnd, String prevLine, String prevLineLower,
                               String nextLine, String nextLineLower,
                               boolean[] lineHasOwnLabel,
                               float[] lineUnitMultiplier, boolean[] lineAbsoluteUnit,
                               boolean[] lineUsedAsValue,
                               Set<String> consumedTokens, Set<String> processedTests,
                               Map<String, TestData> testsByName, List<ParsedTestItem> results) {

        String suffix      = line.substring(Math.min(kwEnd, line.length())).trim();
        String suffixLower = suffix.toLowerCase();

        // ── MCH / MCHC guard ─────────────────────────────────────────────────
        // "MCH" is a 3-char substring of "MCHC". findWholeWord() already
        // prevents matching "mch" INSIDE "mchc" (after char is 'c').
        // However, if the OCR clusters both labels on the same line
        // (e.g. "MCH MCHC 32.20 g/dl 31.5-34.5"), "mch" still matches
        // the standalone "MCH" at the line start and then incorrectly
        // picks up MCHC's value from the suffix.  Guard: if the matched
        // key is the MCH abbreviation AND "mchc" appears ANYWHERE on
        // this line, skip — MCHC's own entry will pick the value up.
        if ("mch".equals(entry.searchKey) && lineLower.contains("mchc")) return;

        // Tracks which line the value actually came from, so the line can be
        // marked "used" below — Pass 3 (unmatched-parameter scan) must never
        // re-claim it as a separate "unknown" row.
        int valueLineIdx = lineIdx;

        // Attempt 1: same-line suffix (primary — RULE 1)
        ValueMatch vm = extractValue(suffix, suffixLower, entry,
                                     lineIdx, kwEnd, consumedTokens);

        // Attempt 2: next-line fallback (RULE 1 secondary) — but ONLY if the
        // next line isn't itself the start of a DIFFERENT test's row. If it
        // is, that number belongs to the next test, not this one (Attempt 3
        // below handles the layouts where this distinction matters).
        boolean nextLineOwnedByOtherTest =
                (lineIdx + 1 < lineHasOwnLabel.length) && lineHasOwnLabel[lineIdx + 1];

        if (vm == null
                && !nextLineOwnedByOtherTest
                && !nextLine.trim().isEmpty()
                && !isPureMetadataLine(nextLineLower)
                && !isReportHeaderLine(nextLineLower)) {
            vm = extractValue(nextLine.trim(), nextLineLower, entry,
                              lineIdx + 1, 0, consumedTokens);
            if (vm != null) valueLineIdx = lineIdx + 1;
        }

        // Attempt 3 (NEW): previous-line fallback. Some report layouts print
        // a label on its own line while its value/unit/range actually sit on
        // the row ABOVE (seen with MCHC under a bracketed MCV/MCH/MCHC
        // block — OCR groups "MCHC" with the line below it instead of its
        // own value). Only tried once same-line and next-line both fail, and
        // only when the previous line carries no test name of its own —
        // i.e. it's orphaned numeric data, not another test's row.
        if (vm == null
                && lineIdx - 1 >= 0
                && lineIdx - 1 < lineHasOwnLabel.length
                && !lineHasOwnLabel[lineIdx - 1]
                && !prevLine.trim().isEmpty()
                && !isPureMetadataLine(prevLineLower)
                && !isReportHeaderLine(prevLineLower)) {
            vm = extractValue(prevLine.trim(), prevLineLower, entry,
                              lineIdx - 1, 0, consumedTokens);
            if (vm != null) valueLineIdx = lineIdx - 1;
        }

        if (vm == null) return;

        String combinedCtx = prevLine + " " + suffix + " " + nextLine;

        // Absolute-vs-percentage signal for the REDIRECT decision below —
        // read from the ORIGINAL (pre-cleanText) line text for this line
        // and any UNLABELLED neighbour. A labelled neighbour is some
        // OTHER test's own row and must never be borrowed from.
        boolean absoluteUnitNearby = lineAbsoluteUnit[lineIdx];
        if (lineIdx + 1 < lineAbsoluteUnit.length && !lineHasOwnLabel[lineIdx + 1]) {
            absoluteUnitNearby = absoluteUnitNearby || lineAbsoluteUnit[lineIdx + 1];
        }
        if (lineIdx - 1 >= 0 && !lineHasOwnLabel[lineIdx - 1]) {
            absoluteUnitNearby = absoluteUnitNearby || lineAbsoluteUnit[lineIdx - 1];
        }

        // Disambiguation: a Differential-% test ("Neutrophils") and
        // its Absolute-Count counterpart ("Absolute Neutrophil Count
        // (ANC)") are both labelled with the exact same bare word on
        // real reports, so they share this one TestSearchEntry. If
        // THIS line's unit clearly looks like a count rather than a
        // percentage, re-target the match to the Absolute test
        // before checking/marking processedTests — otherwise the
        // Absolute row would always be silently dropped whenever the
        // % row (sharing the same canonical name) was matched first.
        String targetCanonicalName = entry.canonicalName;
        String targetDisplayName   = entry.displayName;
        String targetUnitDefault   = entry.unit;
        String redirectTo = DIFFERENTIAL_TO_ABSOLUTE.get(entry.canonicalName.toLowerCase());
        if (redirectTo != null && absoluteUnitNearby) {
            TestData absoluteTest = testsByName.get(redirectTo);
            if (absoluteTest != null) {
                targetCanonicalName = absoluteTest.getName();
                targetDisplayName   = absoluteTest.getName();
                targetUnitDefault   = absoluteTest.getUnit();
            }
        }

        // Magnitude/scale signal — ONLY worth hunting for in neighbouring
        // lines when the test's OWN unit (after the redirect above) is a
        // cell-count style unit ("/cumm", "/µL", "1000/mm3", "m/µL"...) in
        // the first place. Gating on this stops an unrelated marker on a
        // neighbouring line — the analyzer's "(Model: XN-1000)" sitting
        // near Hemoglobin, "TOTAL COUNT X10^3/uL ..." sitting just before
        // ESR, or RBC's own "X10^6/µL" sitting just before HCT — from
        // being mistaken for THIS test's own scale and wrongly shrinking/
        // inflating its reference range. A genuine count-style test (WBC,
        // Platelet, RBC, the Absolute-Count tests) still searches its own
        // line plus any UNLABELLED neighbour, same as before.
        float reportMultiplier = 1f;
        if (looksLikeAbsoluteCountUnit(targetUnitDefault)) {
            reportMultiplier = lineUnitMultiplier[lineIdx];
            if (lineIdx + 1 < lineUnitMultiplier.length && !lineHasOwnLabel[lineIdx + 1]) {
                reportMultiplier = Math.max(reportMultiplier, lineUnitMultiplier[lineIdx + 1]);
            }
            if (lineIdx - 1 >= 0 && !lineHasOwnLabel[lineIdx - 1]) {
                reportMultiplier = Math.max(reportMultiplier, lineUnitMultiplier[lineIdx - 1]);
            }
        }

        if (processedTests.contains(targetCanonicalName)) return;

        // RULE 3: plausibility gate
        if (!entry.isSubTest && !isPlausibleMedicalValue(vm.value, lineLower)) return;

        // RULE 2: consume the token
        consumedTokens.add(vm.consumeKey);
        processedTests.add(targetCanonicalName);
        lineUsedAsValue[lineIdx] = true;
        if (valueLineIdx >= 0 && valueLineIdx < lineUsedAsValue.length) {
            lineUsedAsValue[valueLineIdx] = true;
        }

        String unit          = extractUnit(combinedCtx, targetUnitDefault);
        float  unitScaleHint = reportMultiplier;

        results.add(new ParsedTestItem(
                targetCanonicalName, targetDisplayName,
                vm.value, unit, entry.isSubTest, unitScaleHint));

        Log.d(TAG, "Matched [" + targetDisplayName + "] = " + vm.value
                + " unit='" + unit + "' scaleHint=" + unitScaleHint
                + " ctx='" + combinedCtx.replace("\n", "\\n") + "'"
                + " @ line " + lineIdx);
    }


    // ══════════════════════════════════════════════════════════════════════════
    // ValueMatch — result + consumption key
    // ══════════════════════════════════════════════════════════════════════════

    private static final class ValueMatch {
        final String value;
        final String consumeKey;   // "lineIdx:charOffset:value"

        ValueMatch(String value, int lineIdx, int charOffset) {
            this.value      = value;
            this.consumeKey = lineIdx + ":" + charOffset + ":" + value;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Core value extraction
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tries to extract the patient result from {@code text} (the suffix after the
     * keyword on the same line, or the full next line as fallback).
     *
     * Strategy (highest → lowest confidence):
     *  1. Qualitative term for sub-tests (Negative/Positive/Trace/etc.)
     *  2. Number immediately before the test's expected unit (unit-anchored)
     *  3. Number that appears BEFORE a reference range "low – high"
     *  4. Number that appears AFTER a reference range (right-column layout)
     *  5. Number after a colon / "result" / "value" label
     *  6. First plausible number not inside a range and not already consumed
     *
     * Every candidate is checked against {@code consumedTokens} before being returned.
     */
    private ValueMatch extractValue(String text, String textLower,
                                    TestSearchEntry entry,
                                    int lineIdx, int baseOffset,
                                    Set<String> consumedTokens) {

        // Strategy 1: qualitative result for urine sub-tests
        if (entry.isSubTest) {
            String qual = extractQualitative(textLower);
            if (qual != null) {
                String ck = lineIdx + ":q:" + qual;
                if (consumedTokens.contains(ck)) return null;
                return new ValueMatch(qual, lineIdx, baseOffset);
            }
        }

        // Shared number pattern: optional L/H flag, then digit sequence
        // NOT followed by 2+ letters (avoids matching inside words like "12g" → "12")
        // NOT followed by "/<letters>" either (avoids matching the leading digits
        // of a unit denominator like "1000/mm3", "1000/cumm" — the Indian/Bangladeshi
        // subcontinent convention for writing thousands-scale cell-count units.
        // Without this, "0.72  1000/mm3  1.5-8.5" lets the fallback strategies below
        // mistake the unit's own "1000" for the patient's actual result).
        //
        // NOTE: the trailing exclusion must itself absorb any leftover digits
        // (\\d* before the slash) — \\d+ is greedy, so on a bare "(?!\\s*/...)"
        // check the engine just backtracks one digit at a time (1000 → 100 →
        // 10 → 1) until the slash is no longer "immediately" after, sneaking
        // a truncated reading of the unit's own number back in as the "value".
        Pattern numPat = Pattern.compile(
                "(?<![a-zA-Z])[LlHh]?\\s*(\\d+(?:\\.\\d+)?)(?![\\d]*[a-zA-Z]{2,})"
                + "(?![\\d]*\\s*/\\s*[a-zA-Z])",
                Pattern.CASE_INSENSITIVE);

        // ── Strategy 2: unit-anchored number ─────────────────────────────────
        if (entry.unit != null && !entry.unit.isEmpty()) {
            // Allow up to 15 chars (dots/spaces) between number and unit
            Pattern unitAnchor = Pattern.compile(
                    "(?<![a-zA-Z])(\\d+(?:\\.\\d+)?)[\\s.]{0,15}"
                    + Pattern.quote(entry.unit),
                    Pattern.CASE_INSENSITIVE);
            Matcher um = unitAnchor.matcher(text);
            while (um.find()) {
                String candidate = um.group(1);
                // Skip if this number is part of a reference range
                if (isRangeBound(text, um.start(1))) continue;
                int    offset = baseOffset + um.start(1);
                String ck     = lineIdx + ":" + offset + ":" + candidate;
                if (consumedTokens.contains(ck)) continue;
                return new ValueMatch(candidate, lineIdx, offset);
            }
        }

        // Reference range pattern used in strategies 3 & 4
        Pattern rangePat = Pattern.compile(
                "(\\d+(?:\\.\\d+)?)\\s*(?:[-–—]|\\bto\\b)\\s*(\\d+(?:\\.\\d+)?)");

        // ── Strategy 3: value BEFORE reference range ──────────────────────────
        Matcher rm = rangePat.matcher(text);
        if (rm.find()) {
            String beforeRange = text.substring(0, rm.start()).trim();
            if (!beforeRange.isEmpty()) {
                int ci = beforeRange.lastIndexOf(':');
                if (ci >= 0) beforeRange = beforeRange.substring(ci + 1).trim();

                Matcher m   = numPat.matcher(beforeRange);
                String last = null;
                int  lastSt = -1;
                while (m.find()) { last = m.group(1); lastSt = m.start(1); }

                if (last != null) {
                    int    offset = baseOffset + lastSt;
                    String ck     = lineIdx + ":" + offset + ":" + last;
                    if (!consumedTokens.contains(ck))
                        return new ValueMatch(last, lineIdx, offset);
                }
            }

            // ── Strategy 4: value AFTER reference range (right-column) ────────
            String afterRange = text.substring(rm.end()).trim();
            if (!afterRange.isEmpty()) {
                Matcher m = numPat.matcher(afterRange);
                if (m.find()) {
                    String candidate = m.group(1);
                    int    offset    = baseOffset + rm.end() + m.start(1);
                    String ck        = lineIdx + ":" + offset + ":" + candidate;
                    if (!consumedTokens.contains(ck))
                        return new ValueMatch(candidate, lineIdx, offset);
                }
            }
        }

        // ── Strategy 5: after colon / "result" / "value" label ────────────────
        int colonIdx  = text.indexOf(':');
        int resultIdx = textLower.indexOf("result");
        int valIdx    = textLower.indexOf("value");
        int markerIdx = firstPositive(colonIdx, resultIdx, valIdx);
        if (markerIdx >= 0) {
            String afterMarker = text.substring(markerIdx + 1).trim();
            Matcher m = numPat.matcher(afterMarker);
            if (m.find()) {
                String candidate = m.group(1);
                int    offset    = baseOffset + markerIdx + 1 + m.start(1);
                String ck        = lineIdx + ":" + offset + ":" + candidate;
                if (!consumedTokens.contains(ck))
                    return new ValueMatch(candidate, lineIdx, offset);
            }
        }

        // ── Strategy 6: first plausible number (last resort) ──────────────────
        Matcher fb = numPat.matcher(text);
        while (fb.find()) {
            String candidate = fb.group(1);
            if (isRangeBound(text, fb.start(1))) continue;   // skip range numbers
            int    offset = baseOffset + fb.start(1);
            String ck     = lineIdx + ":" + offset + ":" + candidate;
            if (consumedTokens.contains(ck)) continue;
            return new ValueMatch(candidate, lineIdx, offset);
        }

        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if the number starting at {@code numStart} is either the
     * lower or upper bound of a reference range "low – high" in {@code text}.
     */
    private boolean isRangeBound(String text, int numStart) {
        Pattern rp = Pattern.compile(
                "(\\d+(?:\\.\\d+)?)\\s*(?:[-–—]|\\bto\\b)\\s*(\\d+(?:\\.\\d+)?)");
        Matcher m = rp.matcher(text);
        while (m.find()) {
            if (m.start(1) == numStart) return true;          // lower bound
            if (m.groupCount() >= 2 && m.start(2) == numStart) return true; // upper bound
        }
        return false;
    }

    private String extractQualitative(String textLower) {
        if (textLower.contains("negative")) return "Negative";
        if (textLower.contains("positive")) return "Positive";
        if (textLower.contains("nil"))      return "Nil";
        if (textLower.contains("trace"))    return "Trace";
        if (textLower.contains("clear"))    return "Clear";
        if (textLower.contains("straw"))    return "Straw";
        if (textLower.contains("turbid"))   return "Turbid";
        if (textLower.contains("+++"))      return "+++";
        if (textLower.contains("++"))       return "++";
        if (textLower.contains("+"))        return "+";
        return null;
    }

    /** RULE 3 — strengthened plausibility check. */
    private boolean isPlausibleMedicalValue(String value, String lineLower) {
        // Block lines with metadata keywords
        if (METADATA_KEYWORD_PATTERN.matcher(lineLower).find()) return false;

        // Block year values on date/dob lines
        if (YEAR_PATTERN.matcher(value).matches()
                && (lineLower.contains("date") || lineLower.contains("year")
                 || lineLower.contains("dob")  || lineLower.contains("report"))) {
            return false;
        }

        // Block integers >= 100 000 without decimal (ID / phone numbers)
        if (!value.contains(".")) {
            try {
                long v = Long.parseLong(value);
                if (v >= 100_000L) return false;
            } catch (NumberFormatException ignored) {}
        }

        // Block 5–7 digit integers with no medical unit on the line (PIN / reg)
        if (PIN_PATTERN.matcher(value).matches()
                && !MEDICAL_UNIT_PATTERN.matcher(lineLower).find()) {
            return false;
        }

        return true;
    }

    /** RULE 4 — true if the line is entirely a report-section heading. */
    private boolean isReportHeaderLine(String lineLower) {
        // Remove punctuation and normalise spaces for comparison
        String stripped = lineLower
                .replaceAll("[^a-z\\s/]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (REPORT_HEADER_SET.contains(stripped)) return true;
        // Also skip if it starts with a header label and has no digits
        if (!lineLower.matches(".*\\d.*")) {
            for (String header : REPORT_HEADER_SET) {
                if (stripped.equals(header)
                        || stripped.startsWith(header + " ")
                        || stripped.endsWith(" " + header)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** RULE 3 — returns true for pure admin / address lines. */
    private boolean isPureMetadataLine(String lineLower) {
        if (!lineLower.matches(".*\\d.*")) {
            if (lineLower.matches(
                    "^(lab|hospital|clinic|diagnostic|centre|center|address|"
                    + "tel|phone|fax|email|www|http|printed|page|"
                    + "doctor|physician|consultant|signed|authorized).*")) {
                return true;
            }
        }
        if (METADATA_KEYWORD_PATTERN.matcher(lineLower).find()) return true;
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demographics extraction — all previous fixes preserved
    // ══════════════════════════════════════════════════════════════════════════

    private Demographics extractDemographics(String rawText) {
        Demographics d = new Demographics();
        String[] lines = rawText.split("\n", -1);
        int limit = Math.min(HEADER_DEMO_LINES, lines.length);

        Pattern ageLabel        = Pattern.compile(
                "(?i)\\bage(?:/sex|/gender)?\\s*[:\\-]?\\s*(\\d{1,3})");
        Pattern ageSuffix       = Pattern.compile(
                "\\b(\\d{1,3})\\s*(?:years?|yrs?|Y)(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
        Pattern sexLabelPattern = Pattern.compile(
                "(?i)(?:sex|gender)\\s*[:\\-]?\\s*(male|female|m\\b|f\\b)");
        Pattern ageSexSlash     = Pattern.compile(
                "(?i)\\bage/sex\\s*[:\\-]?\\s*\\d{1,3}\\s*/\\s*(M(?:ale)?|F(?:emale)?)");
        // Shorthand gender: "27 YRS / M", "34 / F", "25Y/M"
        Pattern shorthandGender = Pattern.compile(
                "(?i)(?:(?:\\d{1,3}\\s*(?:years?|yrs?|y)\\s*(?:/\\s*)?)|(?:/\\s*))"
                + "\\s*\\b(M|F)\\b(?!\\w)");
        // Shorthand gender, FULL WORD form: "45 Yrs/Female", "45Y/Male" — seen on
        // labels like "Age/Gender/DOB : 45 Yrs/Female / 15/01/1980" where the
        // combined label text defeats sexLabelPattern (no bare "sex"/"gender"
        // immediately before the value — "/DOB" sits in between) and ageSexSlash
        // (requires the literal label "age/sex", not "age/gender/dob").
        Pattern shorthandGenderWord = Pattern.compile(
                "(?i)\\b\\d{1,3}\\s*(?:years?|yrs?|y)\\s*/\\s*(male|female)\\b");

        // Name-capture groups stop at 2+ consecutive spaces (each repetition
        // below consumes exactly ONE space). OcrHelper.buildRawText() inserts
        // a wide multi-space gap at detected column boundaries, so this
        // correctly excludes an adjacent column's text (e.g. a right-column
        // "UHID : ..." field) from being captured as part of the name.
        //
        // Colon/dash is OPTIONAL after "patient name"/"pt name" specifically
        // (OCR frequently drops a thin colon glyph, e.g. "Patient Name Master
        // VIDHAN") but stays REQUIRED after the bare "name" alternative,
        // since without that anchor "name" alone is too common a word to
        // safely treat as a label.
        Pattern nameLabelPattern = Pattern.compile(
                "(?i)(?:(?:patient\\s*name|pt\\.?\\s*name)\\s*[:\\-]?\\s*|name\\s*[:\\-]\\s*)"
                + "([A-Za-z][A-Za-z.'-]{0,49}(?: [A-Za-z.'-]{1,49}){0,5})");
        Pattern nameTitlePattern = Pattern.compile(
                "(?i)\\b(?:mr\\.?|mrs\\.?|ms\\.?|dr\\.?|master)\\s+"
                + "([A-Za-z][A-Za-z.'-]{0,49}(?: [A-Za-z.'-]{1,49}){0,5})");

        // Lab/clinic letterhead guard for the title-prefix fallback below.
        // Diagnostic-chain branding very commonly takes the literal form
        // "Dr. <Name> ... Clinical Laboratory/Diagnostics/Pathology" (e.g.
        // "Dr. B. Lal Clinical Laboratory", "Dr. Lal PathLabs") — i.e. it
        // looks EXACTLY like a title-prefixed person's name, but it's the
        // lab's own brand, not the patient. Block any line whose own text OR
        // immediately-following line carries one of these letterhead words.
        Pattern labBrandLine = Pattern.compile(
                "(?i)(laborator(?:y|ies)|diagnostics?|clinical|patholog(?:y|ies)|"
                + "\\blabs?\\b|hospital|health\\s*care|healthcare|\\bcentre\\b|"
                + "\\bcenter\\b|since\\s+(?:19|20)\\d{2}|accredited|nabl|"
                + "serves\\s+best)");

        Pattern reportKeywordPattern = Pattern.compile(
                "(?i)(complete\\s*blood\\s*count|cbc|lipid\\s*profile|liver\\s*function"
                + "|lft|kidney\\s*function|kft|thyroid\\s*function|tsh|blood\\s*sugar"
                + "|urine\\s*routine|urinalysis|haematology|haematological|biochemistry"
                + "|renal\\s*function|hepatic\\s*function|blood\\s*test|serum|pathology"
                + "|clinical\\s*pathology|hematology|coagulation|metabolic\\s*panel"
                + "|basic\\s*metabolic|comprehensive\\s*metabolic|diabetes|thyroid"
                + "|electrolyte|cardiac|iron\\s*study|vitamin|hormone)");
        Pattern reportHeadingPattern = Pattern.compile(
                "(?i)^([A-Za-z][A-Za-z\\s()/-]{3,60})\\s+report\\s*$");
        Pattern testLabelPattern     = Pattern.compile(
                "(?i)(?:test|investigation|panel|report\\s*name|report\\s*type)\\s*[:\\-]\\s*"
                + "([A-Za-z][A-Za-z\\s()/-]{2,60})");

        Pattern standaloneNamePat = Pattern.compile(
                "^([A-Za-z][A-Za-z.'-]{1,49}(?: [A-Za-z.'-]{1,49}){0,5})$");
        Set<String> nameBlacklist = buildNameBlacklist();

        for (int i = 0; i < limit; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // Report name
            if (d.reportName == null) {
                Matcher m = testLabelPattern.matcher(line);
                if (m.find()) d.reportName = toTitleCase(m.group(1).trim());
            }
            if (d.reportName == null) {
                Matcher m = reportKeywordPattern.matcher(line);
                if (m.find()) {
                    String cand = line.replaceAll("(?i)^test\\s*name\\s*[:\\-]\\s*", "").trim();
                    d.reportName = (cand.length() <= 80 && !cand.matches(".*\\d.*"))
                            ? toTitleCase(cand) : toTitleCase(m.group(1).trim());
                }
            }
            if (d.reportName == null) {
                Matcher m = reportHeadingPattern.matcher(line);
                if (m.find()) d.reportName = toTitleCase(m.group(1).trim() + " Report");
            }

            // Patient name — explicit "Patient Name:" label ONLY in this
            // pass. The looser title-prefix fallback (Mr./Mrs./Dr./Master +
            // name) runs in a separate pass below, after every line in this
            // window has had a chance to match the more reliable labeled
            // pattern. This prevents a letterhead line such as
            // "Dr. B. Lal Clinical Laboratory" — which typically appears
            // above the patient-info block — from locking in via the title
            // pattern before the real "Patient Name : ..." line is reached.
            if (d.patientName == null) {
                Matcher m = nameLabelPattern.matcher(line);
                if (m.find()) {
                    String raw = m.group(1).trim();
                    if (isValidName(raw)) d.patientName = toTitleCase(raw);
                }
            }

            // Age
            if (d.age == -1) {
                Matcher m = ageLabel.matcher(line);
                if (m.find()) {
                    try { d.age = Integer.parseInt(m.group(1)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (d.age == -1) {
                Matcher m = ageSuffix.matcher(line);
                if (m.find()) {
                    try { d.age = Integer.parseInt(m.group(1)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            // Sanity correction: ML Kit OCR sometimes inserts a stray leading
            // "1" before a real 2-digit age — confirmed on a report printed
            // "26 Y/ 0 M/ 3 D / F" where OCR read it as "126 Y/O M/3 D/F".
            // No real patient reports a 3-digit age in this kind of report,
            // so treat 100-199 as "leading digit is noise" and keep the last
            // two digits instead of discarding the reading entirely.
            if (d.age >= 100 && d.age <= 199) {
                d.age = d.age % 100;
            }

            // Sex
            if (d.sex == null) {
                Matcher m = sexLabelPattern.matcher(line);
                if (m.find()) d.sex = m.group(1).toLowerCase().startsWith("m") ? "Male" : "Female";
            }
            if (d.sex == null) {
                Matcher m = ageSexSlash.matcher(line);
                if (m.find()) d.sex = m.group(1).toLowerCase().startsWith("m") ? "Male" : "Female";
            }
            if (d.sex == null) {
                Matcher m = shorthandGender.matcher(line);
                if (m.find()) d.sex = m.group(1).equalsIgnoreCase("M") ? "Male" : "Female";
            }
            if (d.sex == null) {
                Matcher m = shorthandGenderWord.matcher(line);
                if (m.find()) d.sex = m.group(1).equalsIgnoreCase("male") ? "Male" : "Female";
            }
        }

        // Second pass: title-prefixed name ("Mr./Mrs./Dr./Master + Name") —
        // only tried if no explicit "Patient Name:" label was found
        // anywhere in the window above. Running this as its own full pass
        // (rather than inline per-line) ensures a letterhead's "Dr. <Name>"
        // can never out-race a later, more reliable "Patient Name: ..." line.
        if (d.patientName == null) {
            for (int i = 0; i < limit && d.patientName == null; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                // Check this line AND the next one together — letterhead text
                // is frequently split across OCR lines ("Dr. B. Lal" / "Clinical
                // Laboratory" / "Serves Best, Serves All" as three separate
                // lines), so the brand keyword may not be on the same line as
                // the "Dr." prefix itself.
                String nearby = line + " " + (i + 1 < limit ? lines[i + 1].trim() : "");
                if (labBrandLine.matcher(nearby).find()) continue;
                if (REFERRED_BY_PATTERN.matcher(line).find()) continue;
                Matcher m = nameTitlePattern.matcher(line);
                if (m.find()) {
                    String raw = m.group(1).trim();
                    if (isValidName(raw)) d.patientName = toTitleCase(raw);
                }
            }
        }

        // Third pass: standalone name line
        if (d.patientName == null) {
            for (int i = 0; i < limit && d.patientName == null; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.matches(".*\\d.*")) continue;
                if (d.reportName != null && line.equalsIgnoreCase(d.reportName)) continue;

                Matcher m = standaloneNamePat.matcher(line);
                if (!m.matches()) continue;

                String   cand  = m.group(1).trim();
                String[] words = cand.split("\\s+");
                if (words.length < 2 && cand.length() < 5) continue;

                boolean blocked = false;
                for (String w : words) {
                    if (nameBlacklist.contains(w.toLowerCase())) { blocked = true; break; }
                }
                if (!blocked && isValidName(cand)) d.patientName = toTitleCase(cand);
            }
        }

        d.reportDate = extractReportDate(lines, limit);

        return d;
    }

    // ── Report date label/format patterns ───────────────────────────────────
    // Priority order matters: a printed "Report Date" or "Sample collection
    // date" reflects when the SPECIMEN was taken/reported, which is what a
    // patient cares about for trending over time — NOT "today", which is
    // merely when this phone happened to scan the page (sometimes years
    // later, as with an old report being digitised now).
    private static final Pattern[] DATE_LABEL_PATTERNS = {
        Pattern.compile("(?i)sample\\s*collection\\s*date\\s*[:\\-]?\\s*(.+)"),
        Pattern.compile("(?i)collect(?:ed|ion)\\s*(?:date|on)?\\s*[:\\-]?\\s*(.+)"),
        Pattern.compile("(?i)report\\s*date\\s*[:\\-]?\\s*(.+)"),
        Pattern.compile("(?i)(?:invoice|order)\\s*date\\s*[:\\-]?\\s*(.+)"),
        Pattern.compile("(?i)\\bdate\\s*[:\\-]\\s*(.+)"),
    };

    /**
     * Scans the header window for a labelled date field and tries each known
     * format against the text that follows the label. Returns null (caller
     * falls back to "now") when nothing parses confidently — this is
     * intentionally conservative: a wrong date is worse than a missing one.
     *
     * SimpleDateFormat instances are built fresh on each call rather than
     * shared as static fields: SimpleDateFormat is NOT thread-safe, and
     * parse() can run concurrently across reports on the background
     * executor in AnalyzeFragment.
     */
    private java.util.Date extractReportDate(String[] lines, int limit) {
        Pattern dayMonthYear = Pattern.compile(
                "(\\d{1,2}[-/][A-Za-z0-9]{2,4}[-/]\\d{2,4})");

        java.text.SimpleDateFormat[] dateFormats = {
            new java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.ENGLISH),
            new java.text.SimpleDateFormat("dd-MMM-yy",   java.util.Locale.ENGLISH),
            new java.text.SimpleDateFormat("dd/MM/yyyy",  java.util.Locale.ENGLISH),
            new java.text.SimpleDateFormat("dd-MM-yyyy",  java.util.Locale.ENGLISH),
            new java.text.SimpleDateFormat("MM/dd/yyyy",  java.util.Locale.ENGLISH),
        };
        for (java.text.SimpleDateFormat fmt : dateFormats) fmt.setLenient(false);

        for (int i = 0; i < limit; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            for (Pattern labelPat : DATE_LABEL_PATTERNS) {
                Matcher lm = labelPat.matcher(line);
                if (!lm.find()) continue;

                String tail = lm.group(1).trim();
                Matcher dm = dayMonthYear.matcher(tail);
                String candidate = dm.find() ? dm.group(1) : tail;

                for (java.text.SimpleDateFormat fmt : dateFormats) {
                    try {
                        java.util.Date parsed = fmt.parse(candidate);
                        // Sanity bound: reject obviously-wrong years (OCR
                        // noise, or a stray phone-number-like token).
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.setTime(parsed);
                        int year = cal.get(java.util.Calendar.YEAR);
                        if (year < 1990 || year > 2035) continue;
                        return parsed;
                    } catch (java.text.ParseException ignored) { }
                }
            }
        }
        return null;
    }

    private boolean isValidName(String raw) {
        if (raw == null || raw.length() < 2 || raw.length() > 60) return false;
        if (raw.equalsIgnoreCase("male") || raw.equalsIgnoreCase("female")) return false;
        if (raw.matches(".*\\d.*")) return false;
        return raw.matches("[A-Za-z][A-Za-z .'-]*");
    }

    private Set<String> buildNameBlacklist() {
        Set<String> s = new HashSet<>();
        for (String w : new String[]{
            "male","female","m","f","report","result","lab","laboratory",
            "test","panel","sample","specimen","date","time","ref","range",
            "normal","abnormal","unit","value","haematology","hematology",
            "biochemistry","pathology","serology","microbiology","urine",
            "blood","serum","plasma","positive","negative","reactive",
            "age","sex","gender","dob","uhid","pid","barcode","accession",
            "doctor","physician","consultant","printed","authorized","page",
            "hospital","clinic","diagnostic","centre","center"
        }) s.add(w);
        return s;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Text cleaning (preserves newlines)
    // ══════════════════════════════════════════════════════════════════════════

    private String cleanText(String raw) {
        String[] lines = raw.split("\n", -1);
        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : lines) {
            String l = line.toLowerCase();

            // OCR digit-character fixes
            l = l.replaceAll("(?<=\\d)[li](?=\\d)", "1");
            l = l.replaceAll("(?<=\\d)o(?=\\d)",    "0");
            l = l.replaceAll("(\\d+),(\\d+)",        "$1.$2");  // comma-decimal → dot

            // ── British → American medical spelling ──────────────────────────
            // Prevents "haemoglobin" from missing dataset alias "hemoglobin".
            // Do these BEFORE any abbreviation stripping so full names survive.
            l = l.replace("haemoglobin", "hemoglobin");
            l = l.replace("haematocrit", "hematocrit");
            l = l.replace("haematology", "hematology");
            l = l.replace("haematological", "hematological");

            // ── Scientific-notation unit stripping ───────────────────────────
            // "238 X 10^3/μL 150-410" → parser's Strategy 3 scans numbers
            // before the reference range and may pick "3" (from "10^3") or
            // a concatenated artefact instead of "238".  Erasing the
            // multiplication-by-power-of-ten unit fragment removes those
            // spurious digits while leaving the patient value ("238") intact.
            //
            // Patterns covered:
            //   X 10^3/μL   X10^3/µL   × 10^6/μL   x 10^9/L
            //   ×10^3/ul    *10^3       10^3/ul      x103/ul (no caret)
            l = l.replaceAll("[x×\\*]\\s*10\\^\\d+/[^\\s]*", "");  // X 10^3/μL etc.
            l = l.replaceAll("[x×\\*]\\s*10\\d+/[^\\s]*", "");      // X103/μL (no caret)
            l = l.replaceAll("\\b10\\^\\d+/[^\\s]*", "");           // standalone 10^3/μL
            l = l.replaceAll("[x×\\*]\\s*10\\^\\d+\\b", "");        // X 10^3 (no slash)

            l = l.replaceAll("[ \\t]+", " ").trim();
            sb.append(l).append('\n');
        }
        return sb.toString().trim();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Parenthesis-aware whole-word matching
    // ══════════════════════════════════════════════════════════════════════════

    private int findWholeWord(String text, String keyword) {
        int from = 0;
        while (from <= text.length() - keyword.length()) {
            int idx = text.indexOf(keyword, from);
            if (idx < 0) return -1;
            char before = (idx > 0)
                    ? text.charAt(idx - 1) : ' ';
            char after  = (idx + keyword.length() < text.length())
                    ? text.charAt(idx + keyword.length()) : ' ';
            if (!Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after))
                return idx;
            from = idx + 1;
        }
        return -1;
    }

    /**
     * Edit-distance-tolerant fallback for findWholeWord(). Real-world OCR
     * doesn't just fail to read a name — it frequently misreads several
     * characters WITHIN it while the surrounding value/unit/range stay
     * legible: "Neutrophils" -> "Netrtts", "Monocytes" -> "Honpovtes",
     * "Eosinophils" -> "tosinoghes". An exact substring search can never
     * recover these; this slides a window across the line and accepts the
     * closest match if it's within a conservative edit-distance budget.
     *
     * Deliberately conservative:
     *   • Keywords under 4 characters are skipped entirely (e.g. "hb", "mch")
     *     — a couple of tolerated edits on a 2-3 letter key would match
     *     almost any short fragment of text, causing false positives.
     *   • The edit-distance budget scales with keyword length but is capped
     *     at 3, and candidate windows must start at a word boundary.
     *
     * Returns {start, matchedLength}, or null if nothing met the threshold.
     */
    private int[] findFuzzyWord(String lineLower, String keyword) {
        int klen = keyword.length();
        if (klen < 4) return null;
        int maxDist = Math.max(1, Math.min(3, klen / 4));

        int bestStart = -1, bestLen = -1, bestDist = maxDist + 1;
        for (int wlen = klen - 2; wlen <= klen + 2; wlen++) {
            if (wlen < 3 || wlen > lineLower.length()) continue;
            for (int start = 0; start + wlen <= lineLower.length(); start++) {
                char before = (start == 0) ? ' ' : lineLower.charAt(start - 1);
                if (Character.isLetterOrDigit(before)) continue;   // must start at a word boundary
                String window = lineLower.substring(start, start + wlen);
                int dist = levenshteinDistance(window, keyword);
                if (dist < bestDist) {
                    bestDist  = dist;
                    bestStart = start;
                    bestLen   = wlen;
                }
            }
        }
        return (bestDist <= maxDist) ? new int[]{bestStart, bestLen} : null;
    }

    /** Standard Levenshtein (edit) distance between two strings. */
    private static int levenshteinDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Unit extraction
    // ══════════════════════════════════════════════════════════════════════════

    private String extractUnit(String context, String defaultUnit) {
        if (defaultUnit == null || defaultUnit.isEmpty()) return "";
        Pattern p = Pattern.compile(
                "(?:^|\\s)" + Pattern.quote(defaultUnit) + "(?:\\s|$)",
                Pattern.CASE_INSENSITIVE);
        return p.matcher(context).find() ? defaultUnit : "";
    }

    /**
     * Detects a clear, explicit absolute-cell-count unit marker near a
     * match — e.g. "/mm3", "/cumm", "/µL", "X10^3" — as opposed to a bare
     * percentage ("%") or no unit at all. Used ONLY to decide whether a
     * Differential-% match ("Neutrophils 5.23 X10^3/µL") should be
     * re-targeted to its Absolute-Count counterpart (see
     * DIFFERENTIAL_TO_ABSOLUTE). Deliberately conservative — requires a
     * POSITIVE count-unit signal rather than just "no % sign found", so a
     * genuine percentage row with a missing/uncaptured unit is never
     * wrongly redirected.
     */
    private boolean looksLikeAbsoluteCountUnit(String context) {
        if (context == null) return false;
        String c = context.toLowerCase();
        return c.contains("/mm3") || c.contains("/mm³")
                || c.contains("/cumm") || c.contains("/µl") || c.contains("/ul")
                || Pattern.compile("x?\\s*10\\s*\\^?\\s*3").matcher(c).find();
    }

    /**
     * Detects the magnitude multiplier implied by RAW TEXT near a match —
     * e.g. "X10^3/µL", "1000/mm3", "thousand/cumm" mean "report value is in
     * thousands"; "Lakhs/cumm" / "lac/cumm" mean "hundred-thousands";
     * "X10^6/µL" / "m/µL" mean "millions". Unlike extractUnit() (which only
     * ever returns the dataset's own literal unit string or ""), this scans
     * the actual context text directly, so it still finds the scale marker
     * even when the report phrases the unit differently than the dataset
     * does. Mirrors ReportAnalyzer.parseUnitMultiplier()'s thresholds so the
     * two stay consistent.
     */
    private float parseContextUnitMultiplier(String context) {
        if (context == null || context.trim().isEmpty()) return 1f;
        String c = context.toLowerCase();
        if (c.contains("lakh") || Pattern.compile("\\blacs?\\b").matcher(c).find()) {
            return 100000f;
        }
        if (c.contains("million")
                || Pattern.compile("x?\\s*10\\s*\\^?\\s*6").matcher(c).find()
                || Pattern.compile("\\bm\\s*/\\s*[uµ]l\\b").matcher(c).find()) {
            return 1000000f;
        }
        if (c.contains("thousand")
                || Pattern.compile("\\b1000\\b").matcher(c).find()
                || Pattern.compile("x?\\s*10\\s*\\^?\\s*3").matcher(c).find()) {
            return 1000f;
        }
        return 1f;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Search-entry builder — RULE 4: only tests with reference ranges
    // ══════════════════════════════════════════════════════════════════════════

    private List<TestSearchEntry> buildSearchEntries(List<TestData> allTests) {
        List<TestSearchEntry> entries   = new ArrayList<>();
        Set<String>           addedKeys = new HashSet<>();

        for (TestData test : allTests) {
            if (test.getName() == null) continue;

            boolean hasRanges = (test.getRanges() != null && !test.getRanges().isEmpty());
            boolean isUrine   = (test.getSubTests() != null && !test.getSubTests().isEmpty());

            // RULE 4: skip tests with no ranges AND no sub-tests
            if (!hasRanges && !isUrine) continue;

            String unit     = (test.getUnit() != null) ? test.getUnit() : "";
            String fullName = test.getName().trim();

            addEntry(entries, addedKeys, fullName.toLowerCase(),
                    fullName, fullName, unit, isUrine, false);
            addParenVariants(entries, addedKeys, fullName, fullName, unit, isUrine, false);

            if (test.getAliases() != null) {
                for (String alias : test.getAliases()) {
                    if (alias == null || alias.trim().isEmpty()) continue;
                    String a = alias.trim();
                    addEntry(entries, addedKeys, a.toLowerCase(),
                            fullName, a, unit, isUrine, false);
                    addParenVariants(entries, addedKeys, a, fullName, unit, isUrine, false);
                }
            }

            if (test.getSubTests() != null) {
                for (SubTest sub : test.getSubTests()) {
                    if (sub.getName() == null) continue;
                    String subUnit = (sub.getUnit() != null && !sub.getUnit().isEmpty())
                            ? sub.getUnit() : unit;
                    String subName = sub.getName().trim();
                    addEntry(entries, addedKeys, subName.toLowerCase(),
                            subName, subName, subUnit, true, true);
                    addParenVariants(entries, addedKeys, subName, subName, subUnit, true, true);
                }
            }
        }

        // RULE 5: sort longest key first — prevents short aliases from
        // matching before the more specific full keyword on the same line
        entries.sort((a, b) -> Integer.compare(b.searchKey.length(), a.searchKey.length()));

        return entries;
    }

    /** Registers outer and inner parts of "Name (Abbrev)" as independent keys. */
    private void addParenVariants(List<TestSearchEntry> entries, Set<String> addedKeys,
                                  String name, String canonicalName, String unit,
                                  boolean isUrine, boolean isSubTest) {
        Matcher pm = Pattern.compile("^(.+?)\\s*\\(([^)]+)\\)\\s*(.*)$").matcher(name);
        if (!pm.matches()) return;
        String outer = (pm.group(1) + " " + pm.group(3)).trim();
        String inner = pm.group(2).trim();
        if (!outer.isEmpty())
            addEntry(entries, addedKeys, outer.toLowerCase(),
                    canonicalName, outer, unit, isUrine, isSubTest);
        if (!inner.isEmpty())
            addEntry(entries, addedKeys, inner.toLowerCase(),
                    canonicalName, inner, unit, isUrine, isSubTest);
    }

    private void addEntry(List<TestSearchEntry> entries, Set<String> addedKeys,
                          String key, String canonicalName, String displayName,
                          String unit, boolean isUrine, boolean isSubTest) {
        if (key.isEmpty()) return;
        if (key.length() == 1) return;   // single-letter keys are too ambiguous
        if (addedKeys.add(key)) {
            entries.add(new TestSearchEntry(key, canonicalName, displayName,
                                            unit, isUrine, isSubTest));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Utility helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static int firstPositive(int... values) {
        int best = -1;
        for (int v : values) {
            if (v >= 0 && (best < 0 || v < best)) best = v;
        }
        return best;
    }

    private static String toTitleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] words = s.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)));
            sb.append(w.substring(1));
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Inner record
    // ══════════════════════════════════════════════════════════════════════════

    private static final class TestSearchEntry {
        final String  searchKey;
        final String  canonicalName;
        final String  displayName;
        final String  unit;
        final boolean isUrine;
        final boolean isSubTest;

        TestSearchEntry(String searchKey, String canonicalName, String displayName,
                        String unit, boolean isUrine, boolean isSubTest) {
            this.searchKey     = searchKey;
            this.canonicalName = canonicalName;
            this.displayName   = displayName;
            this.unit          = unit;
            this.isUrine       = isUrine;
            this.isSubTest     = isSubTest;
        }
    }
}
