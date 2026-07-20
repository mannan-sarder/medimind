package com.mannan.medimind;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * Shared ViewModel scoped to the Activity's lifecycle.
 * Enables safe communication between fragments without direct references.
 *
 * KEY FIX – Thread-safe LiveData updates
 * ────────────────────────────────────────
 * MutableLiveData provides two setter methods:
 *
 *   setValue(T)  – MUST be called on the MAIN thread.
 *                  Calling it from a background thread throws an exception:
 *                  "Cannot invoke setValue on a background thread".
 *
 *   postValue(T) – Can be called from ANY thread.
 *                  It marshals the update to the main thread internally,
 *                  so it is safe to call from Executors, AsyncTask, or coroutines.
 *
 * The original setCurrentResult() called setValue().  In the fixed AnalyzeFragment
 * the analysis work runs on an ExecutorService background thread, so calling
 * setValue() from there would crash the app on the first scan.
 *
 * Fix: all setter methods use postValue() so they remain safe regardless of
 * which thread calls them.  postValue() is idempotent on the main thread too,
 * so there is no downside to always using it here.
 */
public class SharedViewModel extends ViewModel {

    // LiveData for the analysis result – set by AnalyzeFragment after scanning
    private final MutableLiveData<ReportAnalyzer.AnalysisResult> currentAnalysisResult =
            new MutableLiveData<>();

    // LiveData for the selected report ID – set by HistoryFragment when a row is tapped
    private final MutableLiveData<Long> selectedReportId = new MutableLiveData<>();

    // ── Analysis result ───────────────────────────────────────────────────────

    /**
     * Post the analysis result to all observers.
     *
     * FIX: changed from setValue() to postValue() so this method is safe to
     * call from the background executor thread in AnalyzeFragment.
     *
     * @param result Completed analysis result, or null to clear
     */
    public void setCurrentResult(ReportAnalyzer.AnalysisResult result) {
        currentAnalysisResult.postValue(result); // FIX: was setValue() → crash on background thread
    }

    public LiveData<ReportAnalyzer.AnalysisResult> getCurrentResult() {
        return currentAnalysisResult;
    }

    /**
     * Clear the result after ResultFragment has consumed it.
     * Uses postValue(null) to be callable from any thread.
     */
    public void clearCurrentResult() {
        currentAnalysisResult.postValue(null);
    }

    // ── Selected report ID ────────────────────────────────────────────────────

    /**
     * Post the ID of a report selected from HistoryFragment.
     *
     * FIX: postValue() for consistency and background-thread safety.
     *
     * @param id Primary key of the MedicalReport row in Room
     */
    public void setSelectedReportId(long id) {
        selectedReportId.postValue(id); // FIX: was setValue()
    }

    public LiveData<Long> getSelectedReportId() {
        return selectedReportId;
    }

    /**
     * Clear the selected report ID after ResultFragment has consumed it.
     */
    public void clearSelectedReportId() {
        selectedReportId.postValue(null);
    }
}