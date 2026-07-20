package com.mannan.medimind;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.gson.Gson;
import com.mannan.medimind.db.MedicalReport;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment for scanning a medical-report image and running fully offline analysis.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PIPELINE (offline OCR-based)
 * ─────────────────────────────────────────────────────────────────────────────
 *  1. User opens camera or picks from gallery.
 *  2. startProcessing() shows the spinner and calls OcrHelper.recognizeText(),
 *     which decodes the image and runs ML Kit's on-device text recognizer.
 *  3. OcrHelper returns raw recognised text on the MAIN thread via OcrCallback.
 *  4. onSuccess() hands off to a background executor for:
 *       a. ReportParser.parse()      – raw text → structured ParsedReport
 *       b. ReportAnalyzer.analyze()  – cross-references with dataset.json
 *       c. Room persistence (MedicalReport insert)
 *       d. SharedViewModel.setCurrentResult()
 *       e. Navigation to ResultFragment (back on main thread)
 *  5. onFailure() shows the user-readable error in a Toast and re-enables buttons.
 *
 * No network call, no API key, and no remote service are involved anywhere
 * in this pipeline — everything runs on-device.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THREADING SUMMARY
 * ─────────────────────────────────────────────────────────────────────────────
 *  • ML Kit's own internal executor → on-device text recognition
 *  • OcrCallback → delivered on MAIN thread
 *  • 'executor' (single-thread) → parsing + analysis + Room insert
 *  • getActivity().runOnUiThread() → navigation after Room insert
 */
public class AnalyzeFragment extends Fragment {

    private Button      btnOpenCamera, btnChooseGallery;
    private View        cardProcessing;
    private RadioGroup  rgLanguage;
    private RadioButton rbBangla, rbEnglish;

    private Uri    imageUri;
    private String currentPhotoPath;
    private String selectedLanguage = Constants.LANG_BN;

    private OcrHelper        ocrHelper;
    private SharedViewModel  sharedViewModel;

    // Background executor for parsing + analysis + Room I/O
    // (OcrHelper / ML Kit run their own recognition on a separate thread).
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Gson            gson     = new Gson();

    // ── Activity-Result launchers ─────────────────────────────────────────────

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    openCamera();
                } else if (isAdded()) {
                    Toast.makeText(requireContext(),
                            "Camera permission is required to scan reports.",
                            Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null && isAdded()) {
                    imageUri = uri;
                    startProcessing();
                }
            });

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && imageUri != null && isAdded()) {
                    startProcessing();
                } else if (isAdded()) {
                    hideSpinner();
                    Toast.makeText(requireContext(),
                            "Failed to capture image. Please try again.",
                            Toast.LENGTH_SHORT).show();
                }
            });

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_analyze, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnOpenCamera    = view.findViewById(R.id.btnOpenCamera);
        btnChooseGallery = view.findViewById(R.id.btnChooseGallery);
        cardProcessing   = view.findViewById(R.id.cardProcessing);
        rgLanguage       = view.findViewById(R.id.rgLanguage);
        rbBangla         = view.findViewById(R.id.rbBangla);
        rbEnglish        = view.findViewById(R.id.rbEnglish);

        // Default language: read from Settings (falls back to Bangla if
        // the user has never opened Settings, which preserves the app's
        // original default behaviour).
        String defaultLang = requireContext()
                .getSharedPreferences(Constants.PREF_NAME, android.content.Context.MODE_PRIVATE)
                .getString(Constants.KEY_DISPLAY_LANGUAGE, Constants.LANG_BN);
        rbBangla.setChecked(Constants.LANG_BN.equals(defaultLang));
        rbEnglish.setChecked(Constants.LANG_EN.equals(defaultLang));
        selectedLanguage = defaultLang;

        rgLanguage.setOnCheckedChangeListener((group, checkedId) ->
                selectedLanguage = (checkedId == R.id.rbBangla)
                        ? Constants.LANG_BN : Constants.LANG_EN);

        ocrHelper       = new OcrHelper(requireContext());
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        btnOpenCamera.setOnClickListener(v    -> checkCameraAndOpen());
        btnChooseGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));
    }

    // ── Camera helpers ────────────────────────────────────────────────────────

    private void checkCameraAndOpen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void openCamera() {
        try {
            File photo = createImageFile();
            imageUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    photo);
            cameraLauncher.launch(imageUri);
        } catch (IOException e) {
            if (isAdded()) {
                Toast.makeText(requireContext(),
                        "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private File createImageFile() throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        File file = File.createTempFile(
                "JPEG_" + stamp + "_", ".jpg",
                requireContext().getCacheDir());
        currentPhotoPath = file.getAbsolutePath();
        return file;
    }

    // ── Main offline OCR processing pipeline ─────────────────────────────────

    /**
     * Initiates the full offline analysis pipeline.
     *
     *  1. Show spinner, disable buttons.
     *  2. OcrHelper runs ML Kit's on-device text recognizer on the image.
     *  3. On success (MAIN thread) → background executor:
     *       a. ReportParser.parse()     — raw text → ParsedReport
     *       b. ReportAnalyzer.analyze() — dataset cross-reference
     *       c. Room insert (MedicalReport)
     *       d. SharedViewModel update
     *       e. Navigation to ResultFragment
     *  4. On failure (MAIN thread) → Toast error + re-enable buttons.
     */
    private void startProcessing() {
        if (!isAdded() || getContext() == null) return;

        showSpinner();

        // Capture immutable locals for lambda use
        final String  lang      = selectedLanguage;
        final String  photoPath = currentPhotoPath;
        final Uri     uri       = imageUri;
        final android.content.Context appCtx =
                requireContext().getApplicationContext();

        ocrHelper.recognizeText(uri, new OcrHelper.OcrCallback() {

            /**
             * Called on the MAIN thread once ML Kit returns recognised text.
             * Immediately hands off to background executor for heavy parsing,
             * analysis, and DB work.
             */
            @Override
            public void onSuccess(String rawText) {
                // Debug aid: persist the exact raw OCR text BEFORE any parsing
                // happens, so a wrong result can be diagnosed from what ML Kit
                // actually recognised rather than guessed from the photo.
                saveOcrDebugCapture(appCtx, rawText);

                executor.execute(() -> {

                    // Step A: Parse raw OCR text into structured test items
                    ReportParser              parser       = new ReportParser(appCtx);
                    ReportParser.ParsedReport parsedReport = parser.parse(rawText);

                    // Step B: Cross-reference every parameter with dataset.json
                    ReportAnalyzer         analyzer = new ReportAnalyzer(appCtx, lang);
                    ReportAnalyzer.AnalysisResult result =
                            analyzer.analyze(parsedReport);

                    // Step C: Determine report panel name for History list
                    //   Priority: AnalysisResult (demographics header) → inferred
                    String reportType = result.getReportName() != null
                            ? result.getReportName()
                            : inferReportType(result.getTestResults());

                    // Step D: Persist to Room database for offline history
                    MedicalReport record = new MedicalReport();
                    // Prefer the date actually printed on the report (collection/
                    // report date) so History/trending reflects when the test was
                    // really done — not just when this photo happened to get
                    // scanned (which can be long after, e.g. digitising an old
                    // report). Falls back to "now" only when OCR couldn't
                    // confidently parse a date — see ReportParser.extractReportDate().
                    record.setDate(result.getReportDate() != null
                                    ? result.getReportDate() : new Date());
                    record.setSummary(result.getSummary());
                    record.setAnalysisDataJson(new Gson().toJson(result));
                    record.setLocalImagePath(photoPath != null ? photoPath : "");
                    record.setSynced(false);
                    record.setPatientName(result.getPatientName());     // from OCR
                    record.setReportType(reportType);
                    record.setPatientGender(result.getPatientGender()); // from OCR
                    record.setPatientAge(result.getPatientAge());       // from OCR
                    DatabaseHelper.getInstance(appCtx)
                            .reportDao()
                            .insertReport(record);

                    // Step E: Publish result and navigate to ResultFragment
                    sharedViewModel.setCurrentResult(result);

                    if (getActivity() != null && isAdded()) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            hideSpinner();
                            Navigation.findNavController(requireView())
                                    .navigate(
                                        R.id.action_analyzeFragment_to_resultFragment);
                        });
                    }
                });
            }

            /**
             * Called on the MAIN thread if OCR fails (unreadable image, no
             * text detected, or a recognition error). The message is already
             * user-readable (built in OcrHelper).
             */
            @Override
            public void onFailure(String errorMessage) {
                hideSpinner();
                if (isAdded()) {
                    Toast.makeText(requireContext(),
                            errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ── Debug aid: raw OCR text capture ──────────────────────────────────────

    /**
     * Persists the raw OCR text from every scan so parsing bugs can be
     * diagnosed from the ACTUAL recognised text instead of guessing from a
     * photo. Saves two copies:
     *   • SharedPreferences "last_ocr_raw_text" — quick access for the
     *     overflow menu's "Debug: Share Last OCR Text" option
     *     (see DashboardActivity.shareLastOcrText()).
     *   • A timestamped file under filesDir/ocr_debug/ — a running history
     *     of every scan, retrievable via a file manager or
     *     `adb shell run-as <package> ls files/ocr_debug` for deeper digging.
     *
     * Best-effort only: any failure here is logged and swallowed — it must
     * never interrupt the real analysis pipeline.
     */
    private void saveOcrDebugCapture(android.content.Context appCtx, String rawText) {
        try {
            appCtx.getSharedPreferences("MediMindPrefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_ocr_raw_text", rawText)
                    .apply();

            File dir = new File(appCtx.getFilesDir(), "ocr_debug");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File out = new File(dir, "ocr_" + stamp + ".txt");
            try (java.io.FileWriter writer = new java.io.FileWriter(out)) {
                writer.write(rawText != null ? rawText : "");
            }
        } catch (Exception e) {
            android.util.Log.w("MediMind-OcrDebug", "Failed to save OCR debug capture", e);
        }
    }

    // ── Spinner helpers ───────────────────────────────────────────────────────

    private void showSpinner() {
        if (!isAdded()) return;
        cardProcessing.setVisibility(View.VISIBLE);
        btnOpenCamera.setEnabled(false);
        btnChooseGallery.setEnabled(false);
    }

    private void hideSpinner() {
        if (!isAdded()) return;
        cardProcessing.setVisibility(View.GONE);
        btnOpenCamera.setEnabled(true);
        btnChooseGallery.setEnabled(true);
    }

    // ── Report type inference (fallback) ──────────────────────────────────────

    /**
     * Infers a human-readable panel name from the test names when the
     * AnalysisResult provides no reportName (OCR header didn't include one).
     */
    private String inferReportType(List<TestResult> results) {
        if (results == null || results.isEmpty()) return "Medical Report";

        boolean hasCBC     = false;
        boolean hasLipid   = false;
        boolean hasLFT     = false;
        boolean hasKFT     = false;
        boolean hasThyroid = false;
        boolean hasGlucose = false;

        for (TestResult r : results) {
            String n = r.getTestName() != null
                    ? r.getTestName().toLowerCase() : "";
            if (n.contains("hemoglobin") || n.contains("wbc")
                    || n.contains("platelet") || n.contains("rbc")
                    || n.contains("hematocrit") || n.contains("neutrophil")
                    || n.contains("lymphocyte"))                    hasCBC     = true;
            if (n.contains("cholesterol") || n.contains("triglyceride")
                    || n.contains("hdl") || n.contains("ldl"))      hasLipid   = true;
            if (n.contains("bilirubin") || n.contains("sgot")
                    || n.contains("sgpt") || n.contains("alt")
                    || n.contains("ast")  || n.contains("alkaline")) hasLFT     = true;
            if (n.contains("creatinine") || n.contains("urea")
                    || n.contains("bun") || n.contains("uric acid")) hasKFT     = true;
            if (n.contains("tsh") || n.contains("t3")
                    || n.contains("t4"))                             hasThyroid = true;
            if (n.contains("glucose") || n.contains("hba1c"))       hasGlucose = true;
        }

        if (hasCBC)     return "Complete Blood Count (CBC)";
        if (hasLipid)   return "Lipid Profile";
        if (hasLFT)     return "Liver Function Test (LFT)";
        if (hasKFT)     return "Kidney Function Test (KFT)";
        if (hasThyroid) return "Thyroid Function Test";
        if (hasGlucose) return "Blood Sugar Test";
        return "Medical Report";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Release ML Kit's on-device recognizer resources.
        if (ocrHelper != null) {
            ocrHelper.close();
        }
        // executor is intentionally kept alive for potential Fragment re-creation.
    }
}
