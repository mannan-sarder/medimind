package com.mannan.medimind;

/**
 * POJO class to hold comparison data between two reports.
 * Used in CompareFragment to display side-by-side test results.
 */
public class CompareResult {
    private final String testName;
    private final float value1;
    private final float value2;
    private final float percentChange;
    private final Float standardMin;
    private final Float standardMax;

    /**
     * Constructor with all fields.
     * @param testName Name of the test
     * @param value1 Value from first report
     * @param value2 Value from second report (can be NaN if not present)
     * @param percentChange Percentage change between the two values
     * @param standardMin Lower bound of standard range (can be null)
     * @param standardMax Upper bound of standard range (can be null)
     */
    public CompareResult(String testName, float value1, float value2,
                         float percentChange, Float standardMin, Float standardMax) {
        this.testName = testName;
        this.value1 = value1;
        this.value2 = value2;
        this.percentChange = percentChange;
        this.standardMin = standardMin;
        this.standardMax = standardMax;
    }

    // Getters (no setters needed as data is immutable)

    public String getTestName() {
        return testName;
    }

    public float getValue1() {
        return value1;
    }

    public float getValue2() {
        return value2;
    }

    public float getPercentChange() {
        return percentChange;
    }

    public Float getStandardMin() {
        return standardMin;
    }

    public Float getStandardMax() {
        return standardMax;
    }
}