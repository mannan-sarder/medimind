package com.mannan.medimind.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.mannan.medimind.TestResult;

import java.util.List;

@Dao
public interface TestResultDao {

    // Insert a single test result
    @Insert
    void insert(TestResult testResult);

    // Batch insert multiple test results (better performance)
    @Insert
    void insertAll(List<TestResult> testResults);

    // Get all test results for a specific report
    @Query("SELECT * FROM test_results WHERE reportId = :reportId")
    List<TestResult> getTestResultsForReport(long reportId);

    // Delete all test results for a specific report (optional)
    @Query("DELETE FROM test_results WHERE reportId = :reportId")
    void deleteTestResultsForReport(long reportId);

    // Delete ALL test results — used by Settings > "Clear All History & Reports".
    // Must be called alongside ReportDao.deleteAllReports(); there is no FK
    // cascade between the two tables, so both deletes are needed.
    @Query("DELETE FROM test_results")
    void deleteAllTestResults();
}