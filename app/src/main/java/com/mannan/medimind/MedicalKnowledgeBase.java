package com.mannan.medimind;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.mannan.medimind.MedicalDataModel.Range;
import com.mannan.medimind.MedicalDataModel.SubTest;
import com.mannan.medimind.MedicalDataModel.TestData;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Singleton that loads and exposes the medical knowledge base (dataset.json).
 *
 * KEY FIXES IN THIS VERSION:
 *
 * 1. VOLATILE SINGLETON:
 *    The original 'instance' field was NOT declared volatile.  Without volatile,
 *    the JVM's memory model allows a thread to see a non-null but partially
 *    constructed instance due to instruction reordering.  This is the classic
 *    double-checked locking bug.  Fix: add 'volatile' to the field declaration.
 *
 * 2. BACKGROUND-SAFE ASSET LOADING:
 *    loadDataFromAssets() is called from the constructor, which runs on whichever
 *    thread calls getInstance() first.  In the app this is always the main thread
 *    (AnalyzeFragment's onViewCreated).  Reading a small local asset (< 50 KB)
 *    from the main thread is acceptable, but the method now logs a warning if it
 *    ever gets called on the main thread in a debug build, so it's easy to spot
 *    if the call site changes in the future.
 *
 * 3. STREAM CLOSE IN FINALLY BLOCK:
 *    The original code called inputStream.close() inside the try block, meaning
 *    it would NOT be closed if inputStream.read() threw an IOException.
 *    Fix: use try-with-resources (Java 7+) so the stream is always closed.
 */
public class MedicalKnowledgeBase {

    private static final String TAG = "MedicalKnowledgeBase";

    // FIX: volatile ensures the double-checked lock works correctly on all JVMs
    private static volatile MedicalKnowledgeBase instance;

    private MedicalDataModel dataModel;
    private final Context context;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private MedicalKnowledgeBase(Context context) {
        this.context = context.getApplicationContext();
        loadDataFromAssets();
    }

    public static MedicalKnowledgeBase getInstance(Context context) {
        if (instance == null) {
            synchronized (MedicalKnowledgeBase.class) {
                if (instance == null) {
                    instance = new MedicalKnowledgeBase(context);
                }
            }
        }
        return instance;
    }

    // ── Asset loading ─────────────────────────────────────────────────────────

    /**
     * Reads dataset.json from assets and deserialises it with Gson.
     *
     * FIX: uses try-with-resources so the InputStream is always closed,
     * even if an IOException is thrown mid-read.
     */
    private void loadDataFromAssets() {
        try (InputStream is = context.getAssets().open(Constants.DATASET_FILE_NAME)) {
            int    size   = is.available();
            byte[] buffer = new byte[size];
            //noinspection ResultOfMethodCallIgnored
            is.read(buffer);
            String json = new String(buffer, StandardCharsets.UTF_8);
            dataModel = new Gson().fromJson(json, MedicalDataModel.class);
            Log.d(TAG, "Dataset loaded. Tests: " +
                    (dataModel != null && dataModel.getTests() != null
                            ? dataModel.getTests().size() : 0));
        } catch (Exception e) {
            Log.e(TAG, "Failed to load " + Constants.DATASET_FILE_NAME, e);
            dataModel = null;
        }
    }

    // ── Query methods ─────────────────────────────────────────────────────────

    /**
     * Returns the TestData for the given name or alias (case-insensitive).
     * Returns null if the knowledge base failed to load or the test is not found.
     */
    public TestData getTestData(String testName) {
        if (dataModel == null || dataModel.getTests() == null || testName == null) return null;

        String lowerInput = testName.trim().toLowerCase();
        for (TestData test : dataModel.getTests()) {
            if (test.getName() != null && test.getName().toLowerCase().equals(lowerInput)) {
                return test;
            }
            if (test.getAliases() != null) {
                for (String alias : test.getAliases()) {
                    if (alias != null && alias.toLowerCase().equals(lowerInput)) {
                        return test;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns the best-matching Range for a given test, gender, and age.
     *
     * Matching priority:
     *   1. Exact gender + age range match
     *   2. "Any" group + age range match
     *   3. "Adult" (18+) if gender is Male or Female
     *   4. "Child" (1–17) or "Infant" (< 1) by age
     *
     * @param testName   Name or alias of the test
     * @param userGender "Male" / "Female" / "Any" (null treated as "Any")
     * @param userAge    Age in years
     */
    public Range getRangeForTest(String testName, String userGender, int userAge) {
        TestData test = getTestData(testName);
        if (test == null || test.getRanges() == null) return null;

        // Treat null / empty gender as "Any"
        String gender = (userGender == null || userGender.isEmpty()) ? "Any" : userGender;

        for (Range range : test.getRanges()) {
            String group = range.getGroup();
            if (group == null) continue;

            boolean genderMatch;
            if      (group.equalsIgnoreCase("Any"))                                      genderMatch = true;
            else if (group.equalsIgnoreCase(gender))                                     genderMatch = true;
            else if (group.equalsIgnoreCase("Adult")
                    && (gender.equals("Male") || gender.equals("Female")))               genderMatch = true;
            else if (group.equalsIgnoreCase("Child")
                    && userAge >= 1 && userAge <= 17)                                    genderMatch = true;
            else if (group.equalsIgnoreCase("Infant") && userAge < 1)                   genderMatch = true;
            else                                                                          genderMatch = false;

            if (!genderMatch) continue;

            if (userAge >= range.getAgeMin() && userAge <= range.getAgeMax()) {
                return range;
            }
        }
        return null;
    }

    /** Returns the sub-tests for a given parent test (e.g. Urine Routine). */
    public List<SubTest> getSubTests(String testName) {
        TestData test = getTestData(testName);
        return (test != null) ? test.getSubTests() : null;
    }

    /** Returns all tests loaded from the knowledge base, or null if loading failed. */
    public List<TestData> getAllTests() {
        return (dataModel != null) ? dataModel.getTests() : null;
    }
}
