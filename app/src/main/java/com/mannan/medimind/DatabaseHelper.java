package com.mannan.medimind;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.mannan.medimind.db.MedicalReport;
import com.mannan.medimind.db.ReportDao;
import com.mannan.medimind.db.TestResultDao;
import com.mannan.medimind.remind.Remind;
import com.mannan.medimind.remind.RemindDao;

/**
 * Room database singleton for MediMind.
 *
 * SCHEMA HISTORY
 * ──────────────
 * v1 → v2  MIGRATION_1_2 : Added test_results and reminders tables.
 * v2 → v3  MIGRATION_2_3 : Added patientName, reportType to reports table.
 * v3 → v4  MIGRATION_3_4 : Added patientGender, patientAge to reports table.
 *                          These store the patient's gender and age extracted
 *                          directly from the OCR header so ResultFragment can
 *                          display the correct demographics for every report,
 *                          even when the report belongs to a different person
 *                          than the logged-in user (e.g. a child's report).
 *
 * NEVER use fallbackToDestructiveMigration() – it wipes all stored reports.
 */
@Database(
        entities  = { MedicalReport.class, TestResult.class, Remind.class },
        version   = 4,
        exportSchema = true
)
@TypeConverters(Converters.class)
public abstract class DatabaseHelper extends RoomDatabase {

    private static volatile DatabaseHelper INSTANCE;

    public abstract ReportDao     reportDao();
    public abstract TestResultDao testResultDao();
    public abstract RemindDao     remindDao();

    // ── Migration 1 → 2 ──────────────────────────────────────────────────
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `test_results` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`reportId` INTEGER NOT NULL, `testName` TEXT, " +
                "`value` REAL NOT NULL, `valueStr` TEXT, `unit` TEXT, " +
                "`status` TEXT, `explanation` TEXT, " +
                "`referenceMin` REAL, `referenceMax` REAL, " +
                "`isNumeric` INTEGER NOT NULL DEFAULT 1, " +
                "`questionsForDoctor` TEXT)"
            );
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminders` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`medicineName` TEXT, `hour` INTEGER NOT NULL, " +
                "`minute` INTEGER NOT NULL, " +
                "`isActive` INTEGER NOT NULL DEFAULT 1, " +
                "`isAlarm` INTEGER NOT NULL DEFAULT 1, " +
                "`isNotification` INTEGER NOT NULL DEFAULT 1)"
            );
        }
    };

    // ── Migration 2 → 3 ──────────────────────────────────────────────────
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "ALTER TABLE `reports` ADD COLUMN `patientName` TEXT DEFAULT NULL"
            );
            db.execSQL(
                "ALTER TABLE `reports` ADD COLUMN `reportType` TEXT DEFAULT NULL"
            );
        }
    };

    // ── Migration 3 → 4 ──────────────────────────────────────────────────
    // Adds patient gender (text) and age (integer, -1 = unknown).
    // Both use safe defaults so every existing row remains valid without
    // needing a back-fill.  AnalyzeFragment will populate them for all new
    // scans going forward.
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "ALTER TABLE `reports` ADD COLUMN `patientGender` TEXT DEFAULT NULL"
            );
            db.execSQL(
                "ALTER TABLE `reports` ADD COLUMN `patientAge` INTEGER NOT NULL DEFAULT -1"
            );
        }
    };

    // ── Singleton factory ─────────────────────────────────────────────────
    public static DatabaseHelper getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (DatabaseHelper.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    DatabaseHelper.class,
                                    Constants.DATABASE_NAME
                            )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
