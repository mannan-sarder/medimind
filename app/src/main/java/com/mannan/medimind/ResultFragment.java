package com.mannan.medimind;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mannan.medimind.MedicalDataModel.Reference;
import com.mannan.medimind.db.MedicalReport;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays the full analysis result for one medical-report scan.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * STRICT DISPLAY ORDER
 * ─────────────────────────────────────────────────────────────────────────────
 *  1. HEADER CARD (layout_result_header.xml)
 *     • Report Name  (OCR-extracted → inferred from tests)
 *     • Patient Name (from OCR extraction)
 *     • Gender  |  Age  (from OCR extraction)
 *     • Date scanned
 *     • Summary  (green = all normal, red = abnormal found)
 *
 *  2. PARAMETER CARDS  (item_test_result.xml — one per test)
 *     For every identified test, in order:
 *       a. Test Name (English) + Bangla name if different
 *       b. Value + Unit
 *       c. Status badge: Normal / Low / High / Unknown
 *       d. Normal reference range (when available)
 *       e. Full Explanation  (from dataset.json)
 *       f. Doctor Questions  (from dataset.json, Low/High only)
 *       g. References        (from dataset.json)
 *
 *  3. ABNORMAL FINDINGS SUMMARY (compact cards at bottom)
 *     Shown only when at least one abnormal result exists.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DATA SOURCES
 * ─────────────────────────────────────────────────────────────────────────────
 *  Path A – Live scan:   SharedViewModel.getCurrentResult() LiveData
 *  Path B – History:     Room database via SharedViewModel.getSelectedReportId()
 */
public class ResultFragment extends Fragment {

    private LinearLayout llPatientInfo;
    private LinearLayout llTestResults;
    private TextView     tvAbnormalSectionTitle;
    private LinearLayout llAbnormalFindings;
    private Button       btnCloseResult;

    private SharedViewModel   sharedViewModel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Gson            gson     = new Gson();

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_result, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        llPatientInfo          = view.findViewById(R.id.llPatientInfo);
        llTestResults          = view.findViewById(R.id.llTestResults);
        tvAbnormalSectionTitle = view.findViewById(R.id.tvAbnormalSectionTitle);
        llAbnormalFindings     = view.findViewById(R.id.llAbnormalFindings);
        btnCloseResult         = view.findViewById(R.id.btnCloseResult);

        sharedViewModel = new ViewModelProvider(requireActivity())
                .get(SharedViewModel.class);

        // Path A: live scan just completed
        sharedViewModel.getCurrentResult().observe(getViewLifecycleOwner(), result -> {
            if (result != null) {
                renderResult(result, null);
                sharedViewModel.clearCurrentResult();
            }
        });

        // Path B: report opened from History
        sharedViewModel.getSelectedReportId().observe(getViewLifecycleOwner(), reportId -> {
            if (reportId != null && reportId > 0) {
                loadFromDatabase(reportId);
                sharedViewModel.clearSelectedReportId();
            }
        });

        btnCloseResult.setOnClickListener(v ->
                Navigation.findNavController(v).navigateUp());
    }

    // ── Path B: load from Room ────────────────────────────────────────────────

    private void loadFromDatabase(long reportId) {
        if (!isAdded()) return;
        executor.execute(() -> {
            MedicalReport record = DatabaseHelper.getInstance(requireContext())
                    .reportDao().getReportById(reportId);

            if (getActivity() == null || !isAdded()) return;

            getActivity().runOnUiThread(() -> {
                if (record == null) {
                    Toast.makeText(requireContext(),
                            "Report not found.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String json = record.getAnalysisDataJson();
                if (json == null || json.isEmpty()) {
                    Toast.makeText(requireContext(),
                            "Report data is empty.", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    Type type = new TypeToken<ReportAnalyzer.AnalysisResult>() {}.getType();
                    ReportAnalyzer.AnalysisResult result =
                            gson.fromJson(json, type);
                    if (result != null) {
                        renderResult(result, record);
                    } else {
                        Toast.makeText(requireContext(),
                                "Failed to parse report data.",
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            "Failed to load report: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ── Rendering entry point ─────────────────────────────────────────────────

    /**
     * Renders the full result in strict display order:
     *   1. Header   (renderPatientInfoHeader)
     *   2. Parameter cards + abnormal summary   (renderTestCards)
     */
    private void renderResult(ReportAnalyzer.AnalysisResult result,
                              @Nullable MedicalReport record) {
        if (!isAdded()) return;
        renderPatientInfoHeader(result, record);  // 1. HEADER
        renderTestCards(result.getTestResults()); // 2. PARAMETERS + 3. ABNORMAL
    }

    // ── 1. Patient info header ────────────────────────────────────────────────

    /**
     * Inflates layout_result_header.xml and populates (in order):
     *   • Report Name  – OCR → DB record → inferred from tests
     *   • Patient Name – OCR → DB record → "Unknown Patient"
     *   • Gender / Age – OCR → DB record → user profile
     *   • Scan date    – from DB record, or "—" for freshly-scanned live result
     *   • Summary      – green (all normal) or red (abnormal found)
     */
    private void renderPatientInfoHeader(ReportAnalyzer.AnalysisResult result,
                                          @Nullable MedicalReport record) {
        llPatientInfo.removeAllViews();
        View header = LayoutInflater.from(requireContext())
                .inflate(R.layout.layout_result_header, llPatientInfo, false);

        TextView tvReportType  = header.findViewById(R.id.tvHeaderReportType);
        TextView tvPatientName = header.findViewById(R.id.tvHeaderPatientName);
        TextView tvGenderAge   = header.findViewById(R.id.tvHeaderGenderAge);
        TextView tvDate        = header.findViewById(R.id.tvHeaderDate);
        TextView tvSummary     = header.findViewById(R.id.tvHeaderSummary);

        // ── Report name ───────────────────────────────────────────────────
        String reportName = result.getReportName();                  // OCR
        if (reportName == null && record != null)
            reportName = record.getReportType();                     // DB record
        if (reportName == null)
            reportName = inferReportType(result.getTestResults());   // inferred
        tvReportType.setText(safe(reportName, "Medical Report"));

        // ── Patient name ──────────────────────────────────────────────────
        String patientName = result.getPatientName();                // OCR
        if (patientName == null && record != null)
            patientName = record.getPatientName();                   // DB record
        tvPatientName.setText("Patient: " + safe(patientName, "Unknown Patient"));

        // ── Gender ────────────────────────────────────────────────────────
        String gender = result.getPatientGender();                   // OCR
        if (gender == null && record != null)
            gender = record.getPatientGender();                      // DB record
        if (gender == null) {
            User user = UserManager.getInstance(requireContext()).getCurrentUser();
            if (user != null && user.getGender() != null
                    && !user.getGender().isEmpty()
                    && !Constants.GENDER_ANY.equals(user.getGender())) {
                gender = user.getGender();
            }
        }

        // ── Age ───────────────────────────────────────────────────────────
        int age = result.getPatientAge();                            // OCR
        if (age <= 0 && record != null)
            age = record.getPatientAge();                            // DB record
        if (age <= 0) {
            User user = UserManager.getInstance(requireContext()).getCurrentUser();
            if (user != null && user.getAge() > 0) age = user.getAge();
        }

        String genderStr = (gender != null && !gender.isEmpty()) ? gender : "—";
        String ageStr    = (age > 0) ? age + " yrs" : "—";
        tvGenderAge.setText("Gender: " + genderStr + "   |   Age: " + ageStr);

        // ── Date ──────────────────────────────────────────────────────────
        String dateStr = "—";
        if (record != null && record.getDate() != null) {
            dateStr = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                    .format(record.getDate());
        }
        tvDate.setText("Date: " + dateStr);

        // ── Summary ───────────────────────────────────────────────────────
        String summary = safe(result.getSummary(), "");
        tvSummary.setText(summary);
        boolean hasAbnormal = summary.contains("abnormal")
                || summary.contains("অস্বাভাবিক");
        tvSummary.setTextColor(ContextCompat.getColor(requireContext(),
                hasAbnormal ? R.color.status_color_high : R.color.status_color_normal));

        llPatientInfo.addView(header);
    }

    // ── 2. Parameter cards + 3. Abnormal summary ─────────────────────────────

    /**
     * Inflates one item_test_result.xml card per test result in strict order:
     *   a. Test Name (English) + Bangla name (hidden if absent/same)
     *   b. Value + Unit
     *   c. Status badge (Normal / Low / High / Unknown)
     *   d. Reference range (when available)
     *   e. Explanation   (from dataset.json)
     *   f. Doctor Questions (from dataset.json, Low/High only)
     *   g. References      (from dataset.json)
     *
     * Abnormal results are also added to the compact summary section at bottom.
     */
    private void renderTestCards(List<TestResult> testResults) {
        llTestResults.removeAllViews();
        llAbnormalFindings.removeAllViews();

        if (testResults == null || testResults.isEmpty()) {
            showEmptyState();
            return;
        }

        boolean        hasAbnormal = false;
        LayoutInflater inf         = LayoutInflater.from(requireContext());

        for (TestResult result : testResults) {

            // ── Inflate card ──────────────────────────────────────────────
            View card = inf.inflate(R.layout.item_test_result, llTestResults, false);

            TextView tvTestName    = card.findViewById(R.id.tvTestName);
            TextView tvTestNameBn  = card.findViewById(R.id.tvTestNameBn);
            TextView tvTestValue   = card.findViewById(R.id.tvTestValue);
            TextView tvStatus      = card.findViewById(R.id.tvStatus);
            TextView tvRefRange    = card.findViewById(R.id.tvReferenceRange);
            TextView tvExplanation = card.findViewById(R.id.tvExplanation);
            TextView tvQuestLabel  = card.findViewById(R.id.tvQuestionsLabel);
            TextView tvQuestions   = card.findViewById(R.id.tvQuestions);
            TextView tvRefLabel    = card.findViewById(R.id.tvReferencesLabel);
            TextView tvReferences  = card.findViewById(R.id.tvReferences);

            // ── (a) Test name ─────────────────────────────────────────────
            String testName = safe(result.getTestName(), "N/A");
            tvTestName.setText(testName);

            // ── (b) Value + Unit ──────────────────────────────────────────
            String unit = safe(result.getUnit(), "");
            String valueDisplay;
            if (result.isNumeric()) {
                valueDisplay = fmtFloat(result.getValue())
                        + (unit.isEmpty() ? "" : " " + unit);
            } else {
                valueDisplay = safe(result.getValueStr(), "?");
                if (!unit.isEmpty()) valueDisplay += " " + unit;
            }
            tvTestValue.setText(valueDisplay);

            // ── Bangla test name (hidden if absent or same as English) ────
            String bn = result.getTestNameBn();
            if (bn != null && !bn.isEmpty() && !bn.equals(testName)) {
                tvTestNameBn.setVisibility(View.VISIBLE);
                tvTestNameBn.setText(bn);
            } else {
                tvTestNameBn.setVisibility(View.GONE);
            }

            // ── (c) Status badge ──────────────────────────────────────────
            String status = safe(result.getStatus(), Constants.STATUS_UNKNOWN);
            tvStatus.setText(status);
            applyStatusStyle(tvStatus, status);

            // ── (d) Reference range ───────────────────────────────────────
            if (result.getReferenceMin() != null && result.getReferenceMax() != null) {
                String rangeText = "Normal range: "
                        + fmtFloat(result.getReferenceMin())
                        + " – "
                        + fmtFloat(result.getReferenceMax())
                        + (unit.isEmpty() ? "" : " " + unit);
                tvRefRange.setText(rangeText);
                tvRefRange.setVisibility(View.VISIBLE);
            } else {
                tvRefRange.setVisibility(View.GONE);
            }

            // ── (e) Explanation from dataset.json ─────────────────────────
            String explanation = safe(result.getExplanation(), "");
            if (!explanation.isEmpty()) {
                tvExplanation.setText("Explanation: " + explanation);
                tvExplanation.setVisibility(View.VISIBLE);
            } else {
                tvExplanation.setVisibility(View.GONE);
            }

            // ── (f) Doctor questions from dataset.json ────────────────────
            List<String> questions = result.getQuestionsForDoctor();
            if (questions != null && !questions.isEmpty()) {
                tvQuestLabel.setVisibility(View.GONE);
                tvQuestions.setVisibility(View.VISIBLE);
                StringBuilder qb = new StringBuilder("Questions for your doctor:");
                for (int i = 0; i < questions.size(); i++) {
                    qb.append("\n").append(i + 1).append(". ")
                            .append(questions.get(i));
                }
                tvQuestions.setText(qb.toString());
            } else {
                tvQuestLabel.setVisibility(View.GONE);
                tvQuestions.setVisibility(View.GONE);
            }

            // ── (g) References from dataset.json ──────────────────────────
            List<Reference> refs = result.getReferences();
            if (refs != null && !refs.isEmpty()) {
                StringBuilder rb = new StringBuilder();
                for (int i = 0; i < refs.size(); i++) {
                    String src = refs.get(i).getSource();
                    if (src != null && !src.isEmpty()) {
                        rb.append("• ").append(src);
                        if (i < refs.size() - 1) rb.append("\n");
                    }
                }
                String refText = rb.toString().trim();
                if (!refText.isEmpty()) {
                    tvRefLabel.setVisibility(View.VISIBLE);
                    tvReferences.setVisibility(View.VISIBLE);
                    tvReferences.setText(refText);
                } else {
                    tvRefLabel.setVisibility(View.GONE);
                    tvReferences.setVisibility(View.GONE);
                }
            } else {
                tvRefLabel.setVisibility(View.GONE);
                tvReferences.setVisibility(View.GONE);
            }

            llTestResults.addView(card);

            // ── Collect abnormal entries for summary section ──────────────
            if (Constants.STATUS_LOW.equals(status)
                    || Constants.STATUS_HIGH.equals(status)) {
                hasAbnormal = true;
                View abCard = inf.inflate(R.layout.item_abnormal_finding,
                        llAbnormalFindings, false);
                TextView tvAbName = abCard.findViewById(R.id.tvAbnormalTestName);
                TextView tvAbVal  = abCard.findViewById(R.id.tvAbnormalExplanation);
                if (tvAbName != null) tvAbName.setText(testName);
                if (tvAbVal  != null)
                    tvAbVal.setText(valueDisplay + "  [" + status + "]");
                llAbnormalFindings.addView(abCard);
            }
        }

        if (tvAbnormalSectionTitle != null) {
            tvAbnormalSectionTitle.setVisibility(
                    hasAbnormal ? View.VISIBLE : View.GONE);
        }
        llAbnormalFindings.setVisibility(hasAbnormal ? View.VISIBLE : View.GONE);
    }

    // ── Empty state ───────────────────────────────────────────────────────────

    private void showEmptyState() {
        TextView empty = new TextView(requireContext());
        empty.setText(
                "No test parameters were detected in this report image.\n\n"
                + "Tips for a better scan:\n"
                + "• Ensure the image is well-lit and in focus.\n"
                + "• Hold the camera flat and steady above the report.\n"
                + "• Make sure the entire report is visible in the frame.\n"
                + "• Check your internet connection and try again.");
        empty.setTextSize(14f);
        empty.setPadding(16, 24, 16, 24);
        empty.setTextColor(ContextCompat.getColor(requireContext(),
                R.color.instruction_grey));
        llTestResults.addView(empty);

        if (tvAbnormalSectionTitle != null)
            tvAbnormalSectionTitle.setVisibility(View.GONE);
        llAbnormalFindings.setVisibility(View.GONE);
    }

    // ── Status badge colour ───────────────────────────────────────────────────

    private void applyStatusStyle(TextView badge, String status) {
        int textColor, bgColor;
        if      (Constants.STATUS_NORMAL.equals(status)) {
            textColor = R.color.normal_text;  bgColor = R.color.normal_badge_bg;
        } else if (Constants.STATUS_LOW.equals(status)) {
            textColor = R.color.low_text;     bgColor = R.color.status_low_bg;
        } else if (Constants.STATUS_HIGH.equals(status)) {
            textColor = R.color.high_text;    bgColor = R.color.status_high_bg;
        } else {
            textColor = R.color.colorPrimary; bgColor = R.color.status_normal_bg;
        }
        badge.setTextColor(ContextCompat.getColor(requireContext(), textColor));
        badge.setBackgroundColor(ContextCompat.getColor(requireContext(), bgColor));
    }

    // ── Report type inference (fallback) ──────────────────────────────────────

    private String inferReportType(List<TestResult> results) {
        if (results == null || results.isEmpty()) return "Medical Report";

        boolean hasCBC     = false;
        boolean hasLipid   = false;
        boolean hasLFT     = false;
        boolean hasKFT     = false;
        boolean hasThyroid = false;
        boolean hasGlucose = false;

        for (TestResult r : results) {
            String n = r.getTestName() != null ? r.getTestName().toLowerCase() : "";
            if (n.contains("hemoglobin") || n.contains("hb") || n.contains("wbc")
                    || n.contains("platelet") || n.contains("rbc")
                    || n.contains("hematocrit") || n.contains("neutrophil")
                    || n.contains("lymphocyte"))                           hasCBC     = true;
            if (n.contains("cholesterol") || n.contains("triglyceride")
                    || n.contains("hdl") || n.contains("ldl"))            hasLipid   = true;
            if (n.contains("bilirubin") || n.contains("sgot") || n.contains("sgpt")
                    || n.contains("alt") || n.contains("ast")
                    || n.contains("alkaline"))                             hasLFT     = true;
            if (n.contains("creatinine") || n.contains("urea") || n.contains("bun")
                    || n.contains("uric acid"))                            hasKFT     = true;
            if (n.contains("tsh") || n.contains("t3")
                    || n.contains("t4"))                                   hasThyroid = true;
            if (n.contains("glucose") || n.contains("hba1c"))             hasGlucose = true;
        }

        if (hasCBC)     return "Complete Blood Count (CBC)";
        if (hasLipid)   return "Lipid Profile";
        if (hasLFT)     return "Liver Function Test (LFT)";
        if (hasKFT)     return "Kidney Function Test (KFT)";
        if (hasThyroid) return "Thyroid Function Test";
        if (hasGlucose) return "Blood Sugar Test";
        return "Medical Report";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String safe(String s, String def) {
        return (s != null && !s.isEmpty()) ? s : def;
    }

    private String fmtFloat(float v) {
        return (v == Math.floor(v) && !Float.isInfinite(v))
                ? String.valueOf((int) v)
                : String.format(Locale.getDefault(), "%.2f", v);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
