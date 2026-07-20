package com.mannan.medimind.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;
import java.util.Date;

/**
 * Room entity – one saved medical-report scan.
 * Table name: reports
 *
 * SCHEMA HISTORY
 * ──────────────
 * v1 : id, date, summary, analysisDataJson, localImagePath, isSynced, firebaseId
 * v2 : +test_results and +reminders tables (separate migration)
 * v3 : +patientName, +reportType   (MIGRATION_2_3)
 * v4 : +patientGender, +patientAge (MIGRATION_3_4)
 *
 * WHY v4 CHANGES ARE NEEDED
 * ─────────────────────────
 * The Result screen is required to display the patient's Name, Age, and Gender
 * in the header of every report.  Previously, ResultFragment fell back to the
 * logged-in user's stored profile for gender/age, which is wrong when:
 *   • The patient is a child scanned by a parent.
 *   • The user is in guest (skip-login) mode – profile is null.
 *   • Multiple family members' reports are stored under the same account.
 *
 * Fix: Gender and Age are extracted from the OCR header by ReportParser
 * (Demographics) and stored here by AnalyzeFragment alongside patientName.
 * ResultFragment reads all three fields from AnalysisResult (which carries
 * them in the Gson JSON) so the correct patient demographics are always shown.
 *
 * patientAge : -1 means "not detected in OCR header".
 * patientGender : null means "not detected in OCR header".
 */
@Entity(tableName = "reports")
public class MedicalReport implements Serializable {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private Date   date;
    private String summary;
    private String analysisDataJson;
    private String localImagePath;
    private boolean isSynced;
    private String firebaseId;

    // Schema v3
    private String patientName;
    private String reportType;

    // Schema v4 – OCR-extracted patient demographics
    private String patientGender;   // "Male" / "Female" / null
    private int    patientAge = -1; // years; -1 = not detected

    public MedicalReport() {}

    public MedicalReport(Date date, String summary, String analysisDataJson, String localImagePath) {
        this.date             = date;
        this.summary          = summary;
        this.analysisDataJson = analysisDataJson;
        this.localImagePath   = localImagePath;
        this.isSynced         = false;
    }

    // Getters & Setters
    public long    getId()                       { return id; }
    public void    setId(long id)                { this.id = id; }

    public Date    getDate()                     { return date; }
    public void    setDate(Date d)               { this.date = d; }

    public String  getSummary()                  { return summary; }
    public void    setSummary(String s)          { this.summary = s; }

    public String  getAnalysisDataJson()         { return analysisDataJson; }
    public void    setAnalysisDataJson(String j) { this.analysisDataJson = j; }

    public String  getLocalImagePath()           { return localImagePath; }
    public void    setLocalImagePath(String p)   { this.localImagePath = p; }

    public boolean isSynced()                    { return isSynced; }
    public void    setSynced(boolean s)          { this.isSynced = s; }

    public String  getFirebaseId()               { return firebaseId; }
    public void    setFirebaseId(String id)      { this.firebaseId = id; }

    public String  getPatientName()              { return patientName; }
    public void    setPatientName(String n)      { this.patientName = n; }

    public String  getReportType()               { return reportType; }
    public void    setReportType(String t)       { this.reportType = t; }

    public String  getPatientGender()            { return patientGender; }
    public void    setPatientGender(String g)    { this.patientGender = g; }

    public int     getPatientAge()               { return patientAge; }
    public void    setPatientAge(int a)          { this.patientAge = a; }
}
