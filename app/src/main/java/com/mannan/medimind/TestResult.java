package com.mannan.medimind;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.mannan.medimind.MedicalDataModel.Reference;
import com.mannan.medimind.db.MedicalReport;

import java.io.Serializable;
import java.util.List;

/**
 * Room entity representing one analysed test result row.
 * Table name: test_results
 *
 * CHANGES FROM ORIGINAL
 * ─────────────────────
 * Two new @Ignore (transient) fields have been added:
 *
 * 1. testNameBn
 *    The Bangla name of the test (e.g. "মোট শ্বেত রক্তকণিকা (WBC)").
 *    Sourced from TestData.getNameBn() inside ReportAnalyzer and stored
 *    in the Gson JSON so ResultFragment can render it for bilingual display.
 *    It is NOT stored as a Room column because it is always re-derivable
 *    from the knowledge base and the original testName.  @Ignore prevents
 *    Room from complaining about an unknown column.
 *
 * 2. references
 *    The list of medical literature references (e.g. "Mayo Clinic. CBC. 2023.")
 *    shown at the bottom of each test card in ResultFragment.
 *    Also @Ignore / not stored as a column for the same reason.
 *
 * NO SCHEMA CHANGE: both fields are @Ignore, so no migration is needed.
 */
@Entity(tableName = "test_results",
        indices = { @Index("reportId") },
        foreignKeys = @ForeignKey(
                entity        = MedicalReport.class,
                parentColumns = "id",
                childColumns  = "reportId",
                onDelete      = ForeignKey.CASCADE))
public class TestResult implements Serializable {

    @PrimaryKey(autoGenerate = true)
    private long   id;
    private long   reportId;

    private String testName;

    // ── NOT a Room column – for ResultFragment bilingual display only ──────
    @Ignore
    private String testNameBn;

    private float  value;
    private String valueStr;
    private String unit;
    private String status;
    private Float  referenceMin;
    private Float  referenceMax;
    private String explanation;
    private boolean isNumeric;

    // ── NOT a Room column – doctor questions shown in UI only ─────────────
    @Ignore
    private List<String> questionsForDoctor;

    // ── NOT a Room column – references shown in UI only ───────────────────
    @Ignore
    private List<Reference> references;

    // ── Constructors ──────────────────────────────────────────────────────

    public TestResult() {}

    // ── Getters & Setters ─────────────────────────────────────────────────

    public long   getId()                          { return id; }
    public void   setId(long id)                   { this.id = id; }

    public long   getReportId()                    { return reportId; }
    public void   setReportId(long reportId)       { this.reportId = reportId; }

    public String getTestName()                    { return testName; }
    public void   setTestName(String testName)     { this.testName = testName; }

    public String getTestNameBn()                  { return testNameBn; }
    public void   setTestNameBn(String bn)         { this.testNameBn = bn; }

    public float  getValue()                       { return value; }
    public void   setValue(float value)            { this.value = value; }

    public String getValueStr()                    { return valueStr; }
    public void   setValueStr(String valueStr)     { this.valueStr = valueStr; }

    public String getUnit()                        { return unit; }
    public void   setUnit(String unit)             { this.unit = unit; }

    public String getStatus()                      { return status; }
    public void   setStatus(String status)         { this.status = status; }

    public Float  getReferenceMin()                { return referenceMin; }
    public void   setReferenceMin(Float min)       { this.referenceMin = min; }

    public Float  getReferenceMax()                { return referenceMax; }
    public void   setReferenceMax(Float max)       { this.referenceMax = max; }

    public String getExplanation()                 { return explanation; }
    public void   setExplanation(String exp)       { this.explanation = exp; }

    public boolean isNumeric()                     { return isNumeric; }
    public void    setNumeric(boolean numeric)     { this.isNumeric = numeric; }

    public List<String>    getQuestionsForDoctor()              { return questionsForDoctor; }
    public void            setQuestionsForDoctor(List<String> q){ this.questionsForDoctor = q; }

    public List<Reference> getReferences()                      { return references; }
    public void            setReferences(List<Reference> refs)  { this.references = refs; }
}
