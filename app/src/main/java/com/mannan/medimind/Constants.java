package com.mannan.medimind;

/**
 * Application-wide compile-time constants for MediMind.
 *
 * KEY FIX – DATABASE_VERSION corrected from 1 to 2.
 *
 * The original file declared DATABASE_VERSION = 1, but the @Database annotation
 * in DatabaseHelper.java specifies version = 2 (because the Remind entity was
 * added in v2).  This mismatch did not cause a runtime crash (Room uses the
 * @Database annotation version, not this constant), but it made the code
 * misleading and would cause confusion for any developer who relied on this
 * constant for the migration version number.
 *
 * The constant is now set to 2 to match the actual @Database version.
 */
public final class Constants {

    // Prevent instantiation – this is a constants-only class
    private Constants() {}

    // ── File paths ────────────────────────────────────────────────────────────
    /** Path to the medical knowledge base JSON inside the assets folder. */
    public static final String DATASET_FILE_NAME = "dataset/dataset.json";

    // ── SharedPreferences ─────────────────────────────────────────────────────
    public static final String PREF_NAME          = "MediMindPrefs";
    public static final String KEY_IS_LOGGED_IN   = "is_logged_in";
    public static final String KEY_USER_EMAIL     = "user_email";
    public static final String KEY_USER_NAME      = "user_name";
    public static final String KEY_USER_GENDER    = "user_gender";
    public static final String KEY_USER_AGE       = "user_age";
    public static final String KEY_USER_PHONE     = "user_phone";
    public static final String KEY_USER_ADDRESS   = "user_address";

    // ── Intent / Bundle keys ──────────────────────────────────────────────────
    public static final String KEY_ANALYSIS_RESULT = "analysis_result";
    public static final String KEY_REPORT_ID       = "report_id";
    public static final String KEY_REPORT_OBJECT   = "report_object";

    // ── Room database ─────────────────────────────────────────────────────────
    public static final String DATABASE_NAME = "medimind_db";

    /**
     * FIX: Was 1 in the original.  The actual @Database version is 2 (Remind
     * entity added in v2, MIGRATION_1_2 defined in DatabaseHelper).  Always
     * keep this constant in sync with the @Database(version = …) annotation.
     */
    public static final int DATABASE_VERSION = 2;

    // ── Pagination ────────────────────────────────────────────────────────────
    public static final int DEFAULT_PAGE_SIZE = 10;

    // ── Language codes ────────────────────────────────────────────────────────
    public static final String DEFAULT_LANGUAGE = "en";
    public static final String LANG_EN          = "en";
    public static final String LANG_BN          = "bn";

    // ── Settings screen preferences ───────────────────────────────────────────
    /** Whether reminder notifications play a sound. Default: true. */
    public static final String KEY_NOTIF_SOUND     = "settings_notif_sound";
    /** Whether reminder notifications vibrate. Default: true. */
    public static final String KEY_NOTIF_VIBRATE   = "settings_notif_vibrate";
    /** App theme mode — one of THEME_LIGHT / THEME_DARK / THEME_SYSTEM. */
    public static final String KEY_THEME_MODE      = "settings_theme_mode";
    /** Default report language for Analyze/Compare screens — LANG_EN or LANG_BN. */
    public static final String KEY_DISPLAY_LANGUAGE = "settings_display_language";

    public static final String THEME_LIGHT  = "light";
    public static final String THEME_DARK   = "dark";
    public static final String THEME_SYSTEM = "system";

    // ── OCR image processing limits ───────────────────────────────────────────
    // 1024px was too aggressive a downscale for dense, multi-column lab
    // reports — confirmed from real OCR output where small/condensed rows
    // (Differential Count %, MPV/PCT/PDW, RDW-CV) came out heavily garbled
    // ("Henogbe", "Netrtts", "BDIO-CY") while larger-print rows (ESR, MCV,
    // MCHC) read correctly. Raising the cap gives ML Kit's recognizer more
    // source pixels per character on small text, at the cost of somewhat
    // longer processing (still well within a few seconds on modern phones).
    public static final int OCR_MAX_WIDTH  = 2048;
    public static final int OCR_MAX_HEIGHT = 2048;

    // ── Compare feature ───────────────────────────────────────────────────────
    /** Highlight a metric in the compare table if its % change exceeds this. */
    public static final float PERCENT_CHANGE_THRESHOLD = 10.0f;

    // ── Test status strings ───────────────────────────────────────────────────
    public static final String STATUS_NORMAL  = "Normal";
    public static final String STATUS_LOW     = "Low";
    public static final String STATUS_HIGH    = "High";
    public static final String STATUS_UNKNOWN = "Unknown"; // test not in knowledge base

    // ── Gender types ──────────────────────────────────────────────────────────
    public static final String GENDER_MALE   = "Male";
    public static final String GENDER_FEMALE = "Female";
    public static final String GENDER_ANY    = "Any";
}
