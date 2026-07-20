package com.mannan.medimind;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.gson.Gson;
import com.mannan.medimind.db.MedicalReport;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CompareFragment — দুটি মেডিকেল রিপোর্ট তুলনা করে সম্পূর্ণ বিশ্লেষণ দেখায়।
 *
 * FIXES:
 *  - ScrollView wrapper so results are fully visible (no clipping)
 *  - Full-size result cards per parameter: range, status, explanation from DB,
 *    questions for doctor when abnormal
 *  - Bar chart with proper height
 *  - Local rule-based analysis card (offline, no AI/network call)
 */
public class CompareFragment extends Fragment {

    // ── UI views ──────────────────────────────────────────────────────────
    private LinearLayout              cardReport1, cardReport2;
    private RadioGroup                rgLanguageCompare;
    private String                    selectedLanguage = Constants.LANG_BN;
    private TextView                  tvSelectedReport1, tvSelectedReport2;
    private Button                    btnCompare;
    private CircularProgressIndicator progressBarCompare;
    private LinearLayout              layoutResultsContainer;
    private TextView                  tvResultPlaceholder;

    // ── State ─────────────────────────────────────────────────────────────
    private final List<MedicalReport> allReports = new ArrayList<>();
    private MedicalReport selectedReport1 = null;
    private MedicalReport selectedReport2 = null;

    private int  activeSlot = 0;
    private Uri  cameraImageUri;

    private OcrHelper             ocrHelper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Gson            gson     = new Gson();

    // ── Colors (set in onViewCreated) ─────────────────────────────────────
    private int colorNormal, colorLow, colorHigh, colorPrimary, colorUnknown;
    private int bgLow, bgHigh, bgNormal;

    // ── Activity-Result launchers ─────────────────────────────────────────

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) openCameraInternal();
                else if (isAdded())
                    Toast.makeText(requireContext(), "Camera permission is required.", Toast.LENGTH_SHORT).show();
            });

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && cameraImageUri != null && isAdded())
                    analyzeImageAndAssign(cameraImageUri, activeSlot);
                else if (isAdded()) hideAnalyzeSpinner();
            });

    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null && isAdded()) analyzeImageAndAssign(uri, activeSlot);
                else if (isAdded()) hideAnalyzeSpinner();
            });

    // ── Fragment lifecycle ────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_compare, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        colorNormal  = ContextCompat.getColor(requireContext(), R.color.status_color_normal);
        colorLow     = ContextCompat.getColor(requireContext(), R.color.status_color_low);
        colorHigh    = ContextCompat.getColor(requireContext(), R.color.status_color_high);
        colorPrimary = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
        colorUnknown = Color.parseColor("#607D8B");
        bgNormal     = ContextCompat.getColor(requireContext(), R.color.normal_bg);
        bgLow        = ContextCompat.getColor(requireContext(), R.color.low_bg);
        bgHigh       = ContextCompat.getColor(requireContext(), R.color.high_bg);

        cardReport1            = view.findViewById(R.id.cardReport1);
        cardReport2            = view.findViewById(R.id.cardReport2);
        tvSelectedReport1      = view.findViewById(R.id.tvSelectedReport1);
        tvSelectedReport2      = view.findViewById(R.id.tvSelectedReport2);
        btnCompare             = view.findViewById(R.id.btnCompare);
        progressBarCompare     = view.findViewById(R.id.progressBarCompare);
        layoutResultsContainer = view.findViewById(R.id.layoutResultsContainer);
        tvResultPlaceholder    = view.findViewById(R.id.tvResultPlaceholder);

        ocrHelper = new OcrHelper(requireContext());
        loadReports();

        rgLanguageCompare = view.findViewById(R.id.rgLanguageCompare);
        // Default language: read from Settings (falls back to Bangla, the
        // app's original default, if Settings has never been opened).
        String defaultLang = requireContext()
                .getSharedPreferences(Constants.PREF_NAME, android.content.Context.MODE_PRIVATE)
                .getString(Constants.KEY_DISPLAY_LANGUAGE, Constants.LANG_BN);
        rgLanguageCompare.check(
                Constants.LANG_EN.equals(defaultLang) ? R.id.rbCompareEnglish : R.id.rbCompareBangla);
        selectedLanguage = defaultLang;
        rgLanguageCompare.setOnCheckedChangeListener((group, checkedId) ->
                selectedLanguage = (checkedId == R.id.rbCompareBangla)
                        ? Constants.LANG_BN : Constants.LANG_EN);

        cardReport1.setOnClickListener(v -> showSourceSelectionSheet(1));
        cardReport2.setOnClickListener(v -> showSourceSelectionSheet(2));
        btnCompare.setOnClickListener(v  -> performComparison());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Release ML Kit's on-device recognizer resources.
        if (ocrHelper != null) {
            ocrHelper.close();
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────

    private void loadReports() {
        executor.execute(() -> {
            List<MedicalReport> list = DatabaseHelper.getInstance(requireContext())
                    .reportDao().getAllReports();
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    allReports.clear();
                    if (list != null) allReports.addAll(list);
                });
            }
        });
    }

    // ── Source selection bottom sheet ─────────────────────────────────────

    private void showSourceSelectionSheet(int slot) {
        activeSlot = slot;
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        @SuppressWarnings("InflateParams")
        View sheetView = LayoutInflater.from(requireContext())
                .inflate(R.layout.sheet_report_source, null);
        sheet.setContentView(sheetView);
        sheetView.findViewById(R.id.btnSheetCamera).setOnClickListener(v -> {
            sheet.dismiss(); checkCameraAndOpen();
        });
        sheetView.findViewById(R.id.btnSheetGallery).setOnClickListener(v -> {
            sheet.dismiss(); galleryLauncher.launch("image/*");
        });
        sheetView.findViewById(R.id.btnSheetHistory).setOnClickListener(v -> {
            sheet.dismiss(); showHistorySelectionDialog(slot);
        });
        sheet.show();
    }

    private void checkCameraAndOpen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
            openCameraInternal();
        else
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
    }

    private void openCameraInternal() {
        try {
            File dir   = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File photo = File.createTempFile("CMP_" + ts + "_", ".jpg", dir);
            cameraImageUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider", photo);
            cameraLauncher.launch(cameraImageUri);
        } catch (IOException e) {
            if (isAdded()) Toast.makeText(requireContext(),
                    "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Offline OCR analysis ──────────────────────────────────────────────

    /**
     * Runs the same offline pipeline as AnalyzeFragment for a report being
     * picked directly inside Compare (camera or gallery, not from History):
     *   OcrHelper.recognizeText() → ReportParser.parse() → ReportAnalyzer.analyze()
     */
    private void analyzeImageAndAssign(Uri uri, int slot) {
        showAnalyzeSpinner(slot);
        final android.content.Context appCtx = requireContext().getApplicationContext();
        final String lang = selectedLanguage;
        ocrHelper.recognizeText(uri, new OcrHelper.OcrCallback() {
            @Override public void onSuccess(String rawText) {
                executor.execute(() -> {
                    ReportParser              parser       = new ReportParser(appCtx);
                    ReportParser.ParsedReport parsedReport = parser.parse(rawText);

                    ReportAnalyzer analyzer = new ReportAnalyzer(appCtx, lang);
                    ReportAnalyzer.AnalysisResult result = analyzer.analyze(parsedReport);

                    MedicalReport tempReport = new MedicalReport(
                            new Date(),
                            result != null ? result.getSummary() : "",
                            result != null ? new Gson().toJson(result) : "",
                            uri.toString());
                    if (result != null) {
                        tempReport.setPatientName(result.getPatientName());
                        tempReport.setPatientGender(result.getPatientGender());
                        tempReport.setPatientAge(result.getPatientAge());
                    }
                    if (getActivity() != null && isAdded())
                        getActivity().runOnUiThread(() -> {
                            hideAnalyzeSpinner();
                            assignReport(slot, tempReport);
                        });
                });
            }
            @Override public void onFailure(String errorMessage) {
                if (getActivity() != null && isAdded())
                    getActivity().runOnUiThread(() -> {
                        hideAnalyzeSpinner();
                        Toast.makeText(requireContext(),
                                "Analysis failed: " + errorMessage, Toast.LENGTH_LONG).show();
                    });
            }
        });
    }

    private void showAnalyzeSpinner(int slot) {
        if (progressBarCompare != null) progressBarCompare.setVisibility(View.VISIBLE);
        btnCompare.setEnabled(false);
        TextView tv = (slot == 1) ? tvSelectedReport1 : tvSelectedReport2;
        tv.setText("Analyzing report…");
    }

    private void hideAnalyzeSpinner() {
        if (progressBarCompare != null) progressBarCompare.setVisibility(View.GONE);
        btnCompare.setEnabled(true);
    }

    // ── History selection dialog ──────────────────────────────────────────

    private void showHistorySelectionDialog(int slot) {
        if (allReports.isEmpty()) {
            loadReports();
            Toast.makeText(getContext(),
                    "No saved reports found. Upload a report first.", Toast.LENGTH_SHORT).show();
            return;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        String[] labels = new String[allReports.size()];
        for (int i = 0; i < allReports.size(); i++) {
            MedicalReport r = allReports.get(i);
            String date = (r.getDate() != null) ? sdf.format(r.getDate()) : "Unknown date";
            String name = (r.getPatientName() != null) ? r.getPatientName() : "Unknown Patient";
            if (name.length() > 22) name = name.substring(0, 22) + "…";
            labels[i] = name + "  (" + date + ")";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Select Report " + slot + " from History")
                .setItems(labels, (dialog, which) -> assignReport(slot, allReports.get(which)))
                .show();
    }

    private void assignReport(int slot, MedicalReport report) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        String name = (report.getPatientName() != null) ? report.getPatientName() : "Unknown";
        if (name.length() > 22) name = name.substring(0, 22) + "…";
        String date  = (report.getDate() != null) ? sdf.format(report.getDate()) : "Unknown date";
        String label = name + "  (" + date + ")";
        if (slot == 1) {
            selectedReport1 = report;
            tvSelectedReport1.setText(label);
        } else {
            selectedReport2 = report;
            tvSelectedReport2.setText(label);
            showLatestReportInfoToast();
        }
    }

    private void showLatestReportInfoToast() {
        String msg = selectedLanguage.equals(Constants.LANG_BN)
                ? "✅ Report 2 হলো আপনার সর্বশেষ (Latest) রিপোর্ট।"
                : "✅ Report 2 is your Latest report.";
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    // ── Same-person validation ────────────────────────────────────────────

    @Nullable
    private String validateSamePerson(MedicalReport r1, MedicalReport r2) {
        String n1 = (r1.getPatientName() != null) ? r1.getPatientName().trim().toLowerCase() : "";
        String n2 = (r2.getPatientName() != null) ? r2.getPatientName().trim().toLowerCase() : "";
        if (!n1.isEmpty() && !n2.isEmpty() && !n1.contains(n2) && !n2.contains(n1))
            return "রিপোর্ট দুটি একই রোগীর নয়!\n\nReport 1: " + r1.getPatientName()
                    + "\nReport 2: " + r2.getPatientName()
                    + "\n\nCompare করতে একই ব্যক্তির রিপোর্ট বাছাই করুন।";
        String g1 = r1.getPatientGender(), g2 = r2.getPatientGender();
        if (g1 != null && g2 != null && !g1.isEmpty() && !g2.isEmpty() && !g1.equalsIgnoreCase(g2))
            return "রিপোর্ট দুটির লিঙ্গ ভিন্ন!\nReport 1: " + g1 + "\nReport 2: " + g2;
        int a1 = r1.getPatientAge(), a2 = r2.getPatientAge();
        if (a1 > 0 && a2 > 0 && Math.abs(a1 - a2) > 5)
            return "বয়সের পার্থক্য অনেক বেশি!\nReport 1: " + a1 + " years\nReport 2: " + a2 + " years";
        return null;
    }

    // ── Comparison pipeline ───────────────────────────────────────────────

    private void performComparison() {
        if (selectedReport1 == null) {
            Toast.makeText(getContext(), "Please select Report 1.", Toast.LENGTH_SHORT).show(); return;
        }
        if (selectedReport2 == null) {
            Toast.makeText(getContext(), "Please select Report 2.", Toast.LENGTH_SHORT).show(); return;
        }
        String mismatch = validateSamePerson(selectedReport1, selectedReport2);
        if (mismatch != null) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("⚠️ ভিন্ন রোগীর রিপোর্ট")
                    .setMessage(mismatch)
                    .setPositiveButton("ঠিক আছে", null).show();
            return;
        }

        progressBarCompare.setVisibility(View.VISIBLE);
        btnCompare.setEnabled(false);
        if (tvResultPlaceholder != null) tvResultPlaceholder.setVisibility(View.GONE);

        executor.execute(() -> {
            List<TestResult>   r1             = parseTestResults(selectedReport1);
            List<TestResult>   r2             = parseTestResults(selectedReport2);
            List<CompareResult> compareResults = buildComparison(r1, r2);

            // Chart data — only numeric pairs
            List<String> chartNames = new ArrayList<>();
            List<Float>  chartVals1 = new ArrayList<>();
            List<Float>  chartVals2 = new ArrayList<>();
            for (CompareResult cr : compareResults) {
                if (!Float.isNaN(cr.getValue1()) && !Float.isNaN(cr.getValue2())) {
                    chartNames.add(abbreviate(cr.getTestName(), 8));
                    chartVals1.add(cr.getValue1());
                    chartVals2.add(cr.getValue2());
                }
            }

            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    renderResults(compareResults, chartNames, chartVals1, chartVals2);
                    progressBarCompare.setVisibility(View.GONE);
                    btnCompare.setEnabled(true);
                });
            }
        });
    }

    // ── Render full results ───────────────────────────────────────────────

    private void renderResults(List<CompareResult> results,
                               List<String> chartNames,
                               List<Float>  chartVals1,
                               List<Float>  chartVals2) {
        layoutResultsContainer.removeAllViews();

        // 1. Legend / banner card
        layoutResultsContainer.addView(buildBannerCard());

        // 2. Table header
        layoutResultsContainer.addView(buildTableHeader());

        // 3. Per-parameter rows + expandable detail cards
        MedicalKnowledgeBase kb   = MedicalKnowledgeBase.getInstance(requireContext());
        User user = UserManager.getInstance(requireContext()).getCurrentUser();
        String gender = (user != null && user.getGender() != null) ? user.getGender() : Constants.GENDER_ANY;
        int    age    = (user != null) ? user.getAge() : 25;
        boolean isBn  = selectedLanguage.equals(Constants.LANG_BN);

        int abnormalCount = 0;
        for (int i = 0; i < results.size(); i++) {
            CompareResult cr = results.get(i);
            layoutResultsContainer.addView(buildParamRow(cr, i, isBn));

            // Detail card when abnormal
            String status2 = getStatus(cr.getValue2(), cr.getStandardMin(), cr.getStandardMax());
            if (!"normal".equals(status2) && !"unknown".equals(status2)) {
                MedicalDataModel.TestData td = kb.getTestData(cr.getTestName());
                MedicalDataModel.Range    rng = kb.getRangeForTest(cr.getTestName(), gender, age);
                if (td != null || rng != null) {
                    layoutResultsContainer.addView(buildDetailCard(cr, td, rng, status2, isBn));
                    abnormalCount++;
                }
            }
        }

        // 4. Bar chart (if data available)
        if (!chartNames.isEmpty()) {
            layoutResultsContainer.addView(buildChartCard(chartNames, chartVals1, chartVals2, isBn));
        }

        // 5. Summary badge
        layoutResultsContainer.addView(buildSummaryBadge(results, abnormalCount, isBn));

        // 6. Local rule-based analysis card (offline replacement for the old AI card)
        layoutResultsContainer.addView(buildLocalAnalysisCard(results, isBn));
    }

    // ── Banner card ───────────────────────────────────────────────────────

    private View buildBannerCard() {
        CardView card = new CardView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_blue_tint));
        card.setRadius(dp(12));
        card.setCardElevation(dp(2));

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(14), dp(16), dp(14));

        boolean isBn = selectedLanguage.equals(Constants.LANG_BN);
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        String r2Date = selectedReport2 != null && selectedReport2.getDate() != null
                ? sdf.format(selectedReport2.getDate()) : "—";
        String r1Date = selectedReport1 != null && selectedReport1.getDate() != null
                ? sdf.format(selectedReport1.getDate()) : "—";

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(isBn ? "📊 তুলনার ফলাফল" : "📊 Comparison Result");
        tvTitle.setTextSize(16f); tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(colorPrimary);
        inner.addView(tvTitle);

        TextView tvLegend = new TextView(requireContext());
        String legendText = isBn
                ? "🟢 Report 2 (" + r2Date + ") = সর্বশেষ (Latest) রিপোর্ট\n"
                  + "🔵 Report 1 (" + r1Date + ") = পূর্ববর্তী রিপোর্ট"
                : "🟢 Report 2 (" + r2Date + ") = Latest Report\n"
                  + "🔵 Report 1 (" + r1Date + ") = Previous Report";
        tvLegend.setText(legendText);
        tvLegend.setTextSize(12f); tvLegend.setTextColor(colorPrimary);
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvlp.topMargin = dp(6);
        tvLegend.setLayoutParams(tvlp);
        inner.addView(tvLegend);
        card.addView(inner);
        return card;
    }

    // ── Table header ──────────────────────────────────────────────────────

    private View buildTableHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setWeightSum(4f);
        header.setBackgroundColor(colorPrimary);
        header.setPadding(dp(8), dp(10), dp(8), dp(10));

        boolean isBn = selectedLanguage.equals(Constants.LANG_BN);
        String[] cols = isBn
                ? new String[]{"পরীক্ষা", "আগের", "সর্বশেষ", "পরিবর্তন"}
                : new String[]{"Test", "Previous", "Latest", "Change"};
        float[] weights = {1.5f, 0.9f, 0.9f, 0.7f};

        for (int i = 0; i < cols.length; i++) {
            TextView tv = new TextView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]);
            tv.setLayoutParams(lp);
            tv.setText(cols[i]);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(11f);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setGravity(i == 0 ? Gravity.START : Gravity.CENTER);
            header.addView(tv);
        }
        return header;
    }

    // ── Parameter row ─────────────────────────────────────────────────────

    private View buildParamRow(CompareResult cr, int pos, boolean isBn) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(4f);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));

        String status2 = getStatus(cr.getValue2(), cr.getStandardMin(), cr.getStandardMax());
        int rowBg = (pos % 2 == 0) ? Color.WHITE : Color.parseColor("#F5F6FF");
        if ("low".equals(status2))  rowBg = bgLow;
        if ("high".equals(status2)) rowBg = bgHigh;
        row.setBackgroundColor(rowBg);

        int statusColor = statusColor(status2);
        float[] weights = {1.5f, 0.9f, 0.9f, 0.7f};

        // Col 0: Test name
        TextView tvName = makeColTv(cr.getTestName(), weights[0], colorPrimary, Typeface.BOLD);
        tvName.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(tvName);

        // Col 1: Previous (Report 1)
        row.addView(makeColTv(Float.isNaN(cr.getValue1()) ? "—" : fmt(cr.getValue1()),
                weights[1], Color.parseColor("#546E7A"), Typeface.NORMAL));

        // Col 2: Latest (Report 2) with status color
        TextView tvLatest = makeColTv(Float.isNaN(cr.getValue2()) ? "—" : fmt(cr.getValue2()),
                weights[2], statusColor, Typeface.BOLD);
        // status badge suffix
        if ("low".equals(status2))  tvLatest.setText(tvLatest.getText() + " ↓");
        if ("high".equals(status2)) tvLatest.setText(tvLatest.getText() + " ↑");
        row.addView(tvLatest);

        // Col 3: % change
        if (Float.isNaN(cr.getPercentChange())) {
            row.addView(makeColTv("—", weights[3], colorUnknown, Typeface.NORMAL));
        } else {
            float pct = cr.getPercentChange();
            String arrow = (pct > 0) ? "▲" : (pct < 0) ? "▼" : "●";
            String txt = String.format(Locale.getDefault(), "%s%+.1f%%", arrow, pct);
            int changeColor = Math.abs(pct) < 0.1f ? colorUnknown : (pct > 0 ? colorHigh : colorLow);
            row.addView(makeColTv(txt, weights[3], changeColor, Typeface.BOLD));
        }

        return row;
    }

    // ── Detail card for abnormal values ──────────────────────────────────

    private View buildDetailCard(CompareResult cr,
                                  MedicalDataModel.TestData td,
                                  MedicalDataModel.Range rng,
                                  String status,
                                  boolean isBn) {
        CardView card = new CardView(requireContext());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(4), 0, dp(4), dp(6));
        card.setLayoutParams(cardLp);

        int cardBg = "low".equals(status) ? Color.parseColor("#FFF3E0") : Color.parseColor("#FFF8F8");
        card.setCardBackgroundColor(cardBg);
        card.setRadius(dp(10));
        card.setCardElevation(dp(2));

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(14), dp(12), dp(14), dp(14));

        // Status header
        String statusEmoji = "low".equals(status) ? "🔵" : "🔴";
        String statusLabel = isBn
                ? ("low".equals(status) ? statusEmoji + " কম (Low)" : statusEmoji + " বেশি (High)")
                : ("low".equals(status) ? statusEmoji + " Low" : statusEmoji + " High");
        TextView tvStatus = new TextView(requireContext());
        tvStatus.setText(statusLabel + "  —  " + cr.getTestName());
        tvStatus.setTextSize(13f); tvStatus.setTypeface(null, Typeface.BOLD);
        tvStatus.setTextColor("low".equals(status) ? colorLow : colorHigh);
        inner.addView(tvStatus);

        // Range info
        if (cr.getStandardMin() != null && cr.getStandardMax() != null) {
            String rangeLabel = isBn
                    ? "স্বাভাবিক পরিসীমা: " + fmt(cr.getStandardMin()) + " – " + fmt(cr.getStandardMax())
                    : "Normal range: " + fmt(cr.getStandardMin()) + " – " + fmt(cr.getStandardMax());
            if (td != null && td.getUnit() != null) rangeLabel += "  " + td.getUnit();
            addSubText(inner, rangeLabel, colorUnknown, dp(4));
        }

        // Description from DB
        if (td != null) {
            String desc = isBn ? td.getDescriptionBn() : td.getDescriptionEn();
            if (desc != null && !desc.isEmpty()) {
                addDivider(inner);
                addSubText(inner, "ℹ️  " + desc, Color.parseColor("#37474F"), dp(4));
            }
        }

        // Status explanation from Range
        if (rng != null) {
            String expl = null;
            if ("low".equals(status))  expl = isBn ? rng.getExplanationLowBn()  : rng.getExplanationLowEn();
            if ("high".equals(status)) expl = isBn ? rng.getExplanationHighBn() : rng.getExplanationHighEn();
            if (expl != null && !expl.isEmpty()) {
                // Replace placeholder
                String valStr = Float.isNaN(cr.getValue2()) ? "—" : fmt(cr.getValue2());
                expl = expl.replace("{{user_value}}", valStr);
                addDivider(inner);
                TextView tvExpl = new TextView(requireContext());
                tvExpl.setText("⚕️  " + expl);
                tvExpl.setTextSize(12.5f);
                tvExpl.setTextColor(Color.parseColor("#4A148C"));
                tvExpl.setLineSpacing(dp(2), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                tvExpl.setLayoutParams(lp);
                inner.addView(tvExpl);
            }

            // Questions for doctor
            List<String> qs = null;
            if ("low".equals(status))  qs = isBn ? rng.getQuestionsForDoctorLowBn()  : rng.getQuestionsForDoctorLowEn();
            if ("high".equals(status)) qs = isBn ? rng.getQuestionsForDoctorHighBn() : rng.getQuestionsForDoctorHighEn();
            if (qs != null && !qs.isEmpty()) {
                addDivider(inner);
                String qHeader = isBn ? "🩺 ডাক্তারকে জিজ্ঞেস করুন:" : "🩺 Ask your doctor:";
                addSubText(inner, qHeader, colorPrimary, dp(6));
                for (String q : qs) {
                    addSubText(inner, "  • " + q, Color.parseColor("#1565C0"), dp(2));
                }
            }
        }

        card.addView(inner);
        return card;
    }

    // ── Bar chart card ────────────────────────────────────────────────────

    private View buildChartCard(List<String> names, List<Float> v1, List<Float> v2, boolean isBn) {
        CardView card = new CardView(requireContext());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(16), 0, dp(8));
        card.setLayoutParams(cardLp);
        card.setCardBackgroundColor(Color.WHITE);
        card.setRadius(dp(12));
        card.setCardElevation(dp(3));

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), dp(14), dp(12), dp(14));

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(isBn ? "📈 মানের তুলনামূলক গ্রাফ" : "📈 Values Comparison Chart");
        tvTitle.setTextSize(14f); tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(colorPrimary);
        inner.addView(tvTitle);

        BarChart chart = new BarChart(requireContext());
        // Height: 300dp per bar, max 800dp
        int chartH = Math.min(dp(300) + names.size() * dp(20), dp(800));
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, chartH);
        chartLp.topMargin = dp(10);
        chart.setLayoutParams(chartLp);

        // Build entries
        List<BarEntry> entries1 = new ArrayList<>(), entries2 = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            entries1.add(new BarEntry(i, v1.get(i)));
            entries2.add(new BarEntry(i, v2.get(i)));
        }

        BarDataSet ds1 = new BarDataSet(entries1, isBn ? "পূর্ববর্তী" : "Previous");
        ds1.setColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        ds1.setValueTextSize(9f);

        BarDataSet ds2 = new BarDataSet(entries2, isBn ? "সর্বশেষ" : "Latest");
        ds2.setColor(colorNormal);
        ds2.setValueTextSize(9f);

        final float barWidth = 0.42f, barSpace = 0.04f, groupSpace = 0.08f;
        BarData data = new BarData(ds1, ds2);
        data.setBarWidth(barWidth);

        chart.setData(data);
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(names));
        xAxis.setGranularity(1f);
        xAxis.setCenterAxisLabels(true);
        xAxis.setLabelRotationAngle(-30f);
        xAxis.setTextSize(9f);
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(names.size());

        chart.getAxisLeft().setTextSize(9f);
        chart.getAxisRight().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(true);
        chart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        chart.setFitBars(true);
        chart.groupBars(0f, groupSpace, barSpace);
        chart.setExtraBottomOffset(16f);
        chart.animateY(700);
        chart.invalidate();

        inner.addView(chart);
        card.addView(inner);
        return card;
    }

    // ── Summary badge ─────────────────────────────────────────────────────

    private View buildSummaryBadge(List<CompareResult> results, int abnormal, boolean isBn) {
        int total = results.size();
        int improved = 0, worsened = 0;
        for (CompareResult cr : results) {
            if (!Float.isNaN(cr.getPercentChange())) {
                String s1 = getStatus(cr.getValue1(), cr.getStandardMin(), cr.getStandardMax());
                String s2 = getStatus(cr.getValue2(), cr.getStandardMin(), cr.getStandardMax());
                if (!"normal".equals(s1) && "normal".equals(s2)) improved++;
                if ("normal".equals(s1) && !"normal".equals(s2) && !"unknown".equals(s2)) worsened++;
            }
        }

        CardView card = new CardView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, dp(8));
        card.setLayoutParams(lp);
        card.setCardBackgroundColor(Color.parseColor("#E8F5E9"));
        card.setRadius(dp(12)); card.setCardElevation(dp(2));

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView tvH = new TextView(requireContext());
        tvH.setText(isBn ? "📋 সারসংক্ষেপ" : "📋 Summary");
        tvH.setTextSize(14f); tvH.setTypeface(null, Typeface.BOLD);
        tvH.setTextColor(colorPrimary);
        inner.addView(tvH);

        String[] lines = isBn
                ? new String[]{
                    "মোট পরীক্ষা: " + total,
                    "অস্বাভাবিক মান: " + abnormal,
                    "উন্নতি হয়েছে: " + improved + " টি",
                    "অবনতি হয়েছে: " + worsened + " টি"}
                : new String[]{
                    "Total tests: " + total,
                    "Abnormal values: " + abnormal,
                    "Improved: " + improved,
                    "Worsened: " + worsened};
        for (String line : lines) {
            addSubText(inner, line, Color.parseColor("#1B5E20"), dp(3));
        }
        card.addView(inner); return card;
    }

    // ── Local rule-based comparison summary card ───────────────────────────

    /**
     * Builds a short, fully offline rule-based summary card.
     *
     * Unlike the old remote-API version (which sent the comparison table to
     * a cloud model and rendered whatever text came back), this is entirely
     * rule-based: it counts improved / worsened / unchanged-abnormal values
     * the same way {@link #buildSummaryBadge} does, then lists the specific
     * tests responsible for the most significant changes
     * (|percentChange| >= Constants.PERCENT_CHANGE_THRESHOLD), using
     * explanation text already present in dataset.json wherever possible.
     */
    private View buildLocalAnalysisCard(List<CompareResult> results, boolean isBn) {
        MedicalKnowledgeBase kb     = MedicalKnowledgeBase.getInstance(requireContext());
        User user   = UserManager.getInstance(requireContext()).getCurrentUser();
        String gender = (user != null && user.getGender() != null) ? user.getGender() : Constants.GENDER_ANY;
        int    age    = (user != null) ? user.getAge() : 25;

        int improved = 0, worsened = 0, stillAbnormal = 0;
        List<String> improvedNames    = new ArrayList<>();
        List<String> worsenedNames    = new ArrayList<>();
        List<String> bigChangeNames   = new ArrayList<>();

        for (CompareResult cr : results) {
            String s1 = getStatus(cr.getValue1(), cr.getStandardMin(), cr.getStandardMax());
            String s2 = getStatus(cr.getValue2(), cr.getStandardMin(), cr.getStandardMax());

            if (!"normal".equals(s1) && "normal".equals(s2)) {
                improved++;
                improvedNames.add(cr.getTestName());
            } else if ("normal".equals(s1) && !"normal".equals(s2) && !"unknown".equals(s2)) {
                worsened++;
                worsenedNames.add(cr.getTestName());
            } else if (!"normal".equals(s2) && !"unknown".equals(s2)) {
                stillAbnormal++;
            }

            if (!Float.isNaN(cr.getPercentChange())
                    && Math.abs(cr.getPercentChange()) >= Constants.PERCENT_CHANGE_THRESHOLD) {
                bigChangeNames.add(cr.getTestName());
            }
        }

        CardView card = new CardView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, dp(32));
        card.setLayoutParams(lp);
        card.setCardBackgroundColor(Color.parseColor("#F3F4FF"));
        card.setRadius(dp(14)); card.setCardElevation(dp(3));

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(isBn ? "🔎 বিশ্লেষণ" : "🔎 Analysis");
        tvTitle.setTextSize(14f); tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(colorPrimary);
        inner.addView(tvTitle);

        // ── Overall verdict line ────────────────────────────────────────────
        String verdict;
        if (worsened == 0 && stillAbnormal == 0) {
            verdict = isBn
                    ? "সামগ্রিকভাবে আপনার স্বাস্থ্যের উন্নতি হয়েছে বা স্থিতিশীল আছে।"
                    : "Overall, your health has improved or remained stable.";
        } else if (improved > 0 && worsened > 0) {
            verdict = isBn
                    ? "কিছু পরীক্ষায় উন্নতি হয়েছে, আবার কিছুতে অবনতি হয়েছে — দুই দিকই খেয়াল রাখা প্রয়োজন।"
                    : "Some results improved while others worsened — both deserve attention.";
        } else if (worsened > 0) {
            verdict = isBn
                    ? "কিছু পরীক্ষার ফলাফলে অবনতি দেখা গেছে।"
                    : "Some test results have worsened.";
        } else {
            verdict = isBn
                    ? "কিছু মান এখনও স্বাভাবিক সীমার বাইরে আছে।"
                    : "Some values remain outside the normal range.";
        }
        addSubText(inner, verdict, Color.parseColor("#1A237E"), dp(8));

        // ── Improved tests ───────────────────────────────────────────────
        if (!improvedNames.isEmpty()) {
            addDivider(inner);
            addSubText(inner,
                    isBn ? "✅ উন্নতি হয়েছে:" : "✅ Improved:",
                    Color.parseColor("#1B5E20"), dp(4));
            addSubText(inner, "  " + String.join(", ", improvedNames),
                    Color.parseColor("#2E7D32"), dp(2));
        }

        // ── Worsened tests ───────────────────────────────────────────────
        if (!worsenedNames.isEmpty()) {
            addDivider(inner);
            addSubText(inner,
                    isBn ? "⚠️ অবনতি হয়েছে:" : "⚠️ Worsened:",
                    Color.parseColor("#B71C1C"), dp(4));
            addSubText(inner, "  " + String.join(", ", worsenedNames),
                    Color.parseColor("#C62828"), dp(2));
        }

        // ── Most significant changes, with dataset explanation where available ──
        if (!bigChangeNames.isEmpty()) {
            addDivider(inner);
            addSubText(inner,
                    isBn ? "📌 উল্লেখযোগ্য পরিবর্তন:" : "📌 Notable changes:",
                    colorPrimary, dp(4));
            for (CompareResult cr : results) {
                if (!bigChangeNames.contains(cr.getTestName())) continue;
                String status2 = getStatus(cr.getValue2(), cr.getStandardMin(), cr.getStandardMax());
                MedicalDataModel.Range rng = kb.getRangeForTest(cr.getTestName(), gender, age);
                String note = null;
                if (rng != null) {
                    if ("low".equals(status2))  note = isBn ? rng.getExplanationLowBn()  : rng.getExplanationLowEn();
                    if ("high".equals(status2)) note = isBn ? rng.getExplanationHighBn() : rng.getExplanationHighEn();
                }
                String line = String.format(Locale.getDefault(),
                        "%s (%+.1f%%)", cr.getTestName(), cr.getPercentChange());
                addSubText(inner, "  • " + line, Color.parseColor("#37474F"), dp(2));
                if (note != null && !note.isEmpty()) {
                    String valStr = Float.isNaN(cr.getValue2()) ? "—" : fmt(cr.getValue2());
                    note = note.replace("{{user_value}}", valStr);
                    addSubText(inner, "     " + note, Color.parseColor("#546E7A"), dp(1));
                }
            }
        }

        // ── Doctor-visit advice ─────────────────────────────────────────────
        addDivider(inner);
        String advice = (worsened > 0 || stillAbnormal > 0)
                ? (isBn
                    ? "🩺 অস্বাভাবিক মানগুলো নিয়ে একজন ডাক্তারের সাথে পরামর্শ করুন।"
                    : "🩺 Please consult a doctor about the abnormal values.")
                : (isBn
                    ? "👍 বর্তমানে কোনো বিশেষ চিন্তার কারণ দেখা যাচ্ছে না, তবে নিয়মিত চেক-আপ চালিয়ে যান।"
                    : "👍 No major concern right now, but keep up with regular check-ups.");
        addSubText(inner, advice, Color.parseColor("#1A237E"), dp(6));

        card.addView(inner);
        return card;
    }

    // ── Parse / build comparison ──────────────────────────────────────────

    private List<TestResult> parseTestResults(MedicalReport report) {
        String json = report.getAnalysisDataJson();
        if (json == null || json.isEmpty()) return new ArrayList<>();
        ReportAnalyzer.AnalysisResult result =
                gson.fromJson(json, ReportAnalyzer.AnalysisResult.class);
        return (result != null && result.getTestResults() != null)
                ? result.getTestResults() : new ArrayList<>();
    }

    private List<CompareResult> buildComparison(List<TestResult> r1, List<TestResult> r2) {
        Map<String, TestResult> map1 = new HashMap<>(), map2 = new HashMap<>();
        if (r1 != null) for (TestResult t : r1) map1.put(t.getTestName(), t);
        if (r2 != null) for (TestResult t : r2) map2.put(t.getTestName(), t);

        Set<String> names = new LinkedHashSet<>(map1.keySet());
        names.addAll(map2.keySet());

        boolean r1IsOlder = true;
        if (selectedReport1 != null && selectedReport2 != null
                && selectedReport1.getDate() != null && selectedReport2.getDate() != null)
            r1IsOlder = !selectedReport1.getDate().after(selectedReport2.getDate());

        MedicalKnowledgeBase kb   = MedicalKnowledgeBase.getInstance(requireContext());
        User user = UserManager.getInstance(requireContext()).getCurrentUser();
        String gender = (user != null && user.getGender() != null) ? user.getGender() : Constants.GENDER_ANY;
        int    age    = (user != null) ? user.getAge() : 25;

        List<CompareResult> list = new ArrayList<>();
        for (String name : names) {
            TestResult tr1 = map1.get(name), tr2 = map2.get(name);
            boolean v1Ok = tr1 != null && tr1.isNumeric() && !Float.isNaN(tr1.getValue());
            boolean v2Ok = tr2 != null && tr2.isNumeric() && !Float.isNaN(tr2.getValue());
            if (!v1Ok && !v2Ok) continue;

            String lookup = v1Ok ? tr1.getTestName() : tr2.getTestName();
            MedicalDataModel.Range rng = kb.getRangeForTest(lookup, gender, age);
            Float stdMin = rng != null ? rng.getNormalMin() : null;
            Float stdMax = rng != null ? rng.getNormalMax() : null;

            float val1 = v1Ok ? tr1.getValue() : Float.NaN;
            float val2 = v2Ok ? tr2.getValue() : Float.NaN;
            float older = r1IsOlder ? val1 : val2;
            float newer = r1IsOlder ? val2 : val1;
            float pct   = (!Float.isNaN(older) && !Float.isNaN(newer) && older != 0f)
                    ? ((newer - older) / older) * 100f : Float.NaN;

            list.add(new CompareResult(name, val1, val2, pct, stdMin, stdMax));
        }
        return list;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** "normal" | "low" | "high" | "unknown" */
    private String getStatus(float val, Float min, Float max) {
        if (Float.isNaN(val) || min == null || max == null) return "unknown";
        if (val < min) return "low";
        if (val > max) return "high";
        return "normal";
    }

    private int statusColor(String status) {
        switch (status) {
            case "low":  return colorLow;
            case "high": return colorHigh;
            case "normal": return colorNormal;
            default: return colorUnknown;
        }
    }

    private TextView makeColTv(String text, float weight, int color, int typefaceStyle) {
        TextView tv = new TextView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        if (typefaceStyle != Typeface.NORMAL) tv.setTypeface(null, typefaceStyle);
        tv.setPadding(dp(2), dp(2), dp(2), dp(2));
        return tv;
    }

    private void addSubText(LinearLayout parent, String text, int color, int topMargin) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private void addDivider(LinearLayout parent) {
        View div = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(8), 0, dp(4));
        div.setLayoutParams(lp);
        div.setBackgroundColor(Color.parseColor("#E0E0E0"));
        parent.addView(div);
    }

    private String fmt(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return "N/A";
        return (v == Math.floor(v)) ? String.valueOf((int) v)
                : String.format(Locale.getDefault(), "%.2f", v);
    }

    private String abbreviate(String s, int maxLen) {
        if (s == null) return "";
        // Keep up to first parenthesis or abbreviation
        int paren = s.indexOf('(');
        if (paren > 0 && paren <= maxLen) return s.substring(0, paren).trim();
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private int dp(int val) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(val * density);
    }
}
