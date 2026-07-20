package com.mannan.medimind;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Singleton manager for the user profile.
 * Reads and writes profile data from/to SharedPreferences.
 *
 * KEY FIX – getCurrentUser() can now signal "no profile saved":
 *
 * The original implementation always returned a non-null User with default
 * values (empty strings, age = 25, gender = "Any"), even when the user had
 * tapped "Skip" without entering any profile information.
 *
 * This caused a subtle analysis bug: the ReferenceRange selection in
 * ReportAnalyzer always appeared to have a valid gender/age, so it
 * never fell back to "Any" ranges even when no profile existed.  More
 * confusingly, it masked the OCR-demographics-override logic – the
 * demographics extracted from the report were compared against profile
 * defaults rather than a true absence.
 *
 * Fix: getCurrentUser() now returns null when no profile has ever been saved
 * (i.e. KEY_USER_EMAIL is absent from SharedPreferences).  All call sites
 * that previously assumed a non-null result already have null guards added
 * in their respective files (ReportAnalyzer, CompareFragment).
 *
 * Callers that need a guaranteed non-null User (e.g. for display purposes)
 * can use getOrCreateDefaultUser() which retains the old behaviour.
 */
public class UserManager {

    private static volatile UserManager instance;
    private final SharedPreferences prefs;

    private UserManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
    }

    public static UserManager getInstance(Context context) {
        if (instance == null) {
            synchronized (UserManager.class) {
                if (instance == null) {
                    instance = new UserManager(context);
                }
            }
        }
        return instance;
    }

    // ── Profile access ────────────────────────────────────────────────────────

    /**
     * Returns the stored user profile, or NULL if no profile has been saved.
     *
     * FIX: Returns null when the user skipped the signup/profile step so that
     * ReportAnalyzer and CompareFragment can properly distinguish between
     * "we know the user's demographics" and "we have no profile to fall back to".
     *
     * Use isLoggedIn() to check login state independently of profile completeness.
     */
    public User getCurrentUser() {
        // KEY_USER_EMAIL is only stored after a successful signup or login.
        // Its absence means the user skipped the profile step.
        if (!prefs.contains(Constants.KEY_USER_EMAIL)) {
            return null; // FIX: was returning a default User, masking "no profile"
        }

        String name    = prefs.getString(Constants.KEY_USER_NAME,   "");
        String email   = prefs.getString(Constants.KEY_USER_EMAIL,  "");
        String phone   = prefs.getString(Constants.KEY_USER_PHONE,  "");
        String gender  = prefs.getString(Constants.KEY_USER_GENDER, Constants.GENDER_ANY);
        int    age     = prefs.getInt   (Constants.KEY_USER_AGE,    25);
        String address = prefs.getString(Constants.KEY_USER_ADDRESS, "");

        return new User(name, email, phone, gender, age, address);
    }

    /**
     * Like getCurrentUser() but NEVER returns null.
     * Returns a default User (gender = Any, age = 25) if no profile exists.
     *
     * Use this for UI display where a non-null object is always expected.
     */
    public User getOrCreateDefaultUser() {
        User user = getCurrentUser();
        return (user != null) ? user
                : new User("", "", "", Constants.GENDER_ANY, 25, "");
    }

    /**
     * Save user profile data to SharedPreferences.
     * Persisting KEY_USER_EMAIL is the marker that signals "profile exists"
     * to getCurrentUser().
     */
    public void saveUser(User user) {
        prefs.edit()
                .putString(Constants.KEY_USER_NAME,    user.getName())
                .putString(Constants.KEY_USER_EMAIL,   user.getEmail())
                .putString(Constants.KEY_USER_PHONE,   user.getPhone())
                .putString(Constants.KEY_USER_GENDER,  user.getGender())
                .putInt   (Constants.KEY_USER_AGE,     user.getAge())
                .putString(Constants.KEY_USER_ADDRESS, user.getAddress())
                .apply();
    }

    /**
     * Clears only the profile fields (name, email, phone, gender, age, address)
     * — used by the "Reset Profile" action on the Profile screen.
     *
     * Unlike logout(), this does NOT touch KEY_IS_LOGGED_IN: the user stays
     * logged in but goes back to the same "no profile saved" state as someone
     * who tapped "Skip" on the login screen. getCurrentUser() will return null
     * again afterwards, which ReportAnalyzer/CompareFragment already handle.
     */
    public void clearProfile() {
        prefs.edit()
                .remove(Constants.KEY_USER_NAME)
                .remove(Constants.KEY_USER_EMAIL)
                .remove(Constants.KEY_USER_PHONE)
                .remove(Constants.KEY_USER_GENDER)
                .remove(Constants.KEY_USER_AGE)
                .remove(Constants.KEY_USER_ADDRESS)
                .apply();
    }

    // ── Login state ───────────────────────────────────────────────────────────

    public boolean isLoggedIn() {
        return prefs.getBoolean(Constants.KEY_IS_LOGGED_IN, false);
    }

    public void setLoggedIn(boolean loggedIn) {
        prefs.edit().putBoolean(Constants.KEY_IS_LOGGED_IN, loggedIn).apply();
    }

    /**
     * Clears all stored preferences on logout.
     * After this call, getCurrentUser() returns null and isLoggedIn() returns false.
     */
    public void logout() {
        prefs.edit().clear().apply();
    }
}
