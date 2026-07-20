package com.mannan.medimind.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ReportDao {

    // 1. Insert a new report and return its generated ID (long)
    @Insert
    long insertReport(MedicalReport report);

    // 2. Get all reports ordered by id descending (latest first)
    @Query("SELECT * FROM reports ORDER BY id DESC")
    List<MedicalReport> getAllReports();

    // 3. Get paginated reports for History screen
    @Query("SELECT * FROM reports ORDER BY id DESC LIMIT :limit OFFSET :offset")
    List<MedicalReport> getReportsPaginated(int limit, int offset);

    // 4. Get total number of reports (for pagination calculation)
    @Query("SELECT COUNT(*) FROM reports")
    int getTotalReportsCount();

    // 5. Get a single report by its ID (long type)
    @Query("SELECT * FROM reports WHERE id = :reportId")
    MedicalReport getReportById(long reportId);

    // 6. Update an existing report
    @Update
    void updateReport(MedicalReport report);

    // 7. Delete a report
    @Delete
    void deleteReport(MedicalReport report);

    // 8. Delete report by ID (alternative)
    @Query("DELETE FROM reports WHERE id = :reportId")
    void deleteById(long reportId);

    // 8b. Delete ALL reports — used by Settings > "Clear All History & Reports".
    @Query("DELETE FROM reports")
    void deleteAllReports();

    // 9. Get unsynced reports (for future cloud sync feature)
    @Query("SELECT * FROM reports WHERE isSynced = 0 ORDER BY id DESC")
    List<MedicalReport> getUnsyncedReports();
}