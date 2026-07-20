package com.mannan.medimind;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Custom Application class for MediMind.
 *
 * WHY THIS CLASS EXISTS
 * ──────────────────────
 * AppCompatDelegate.setDefaultNightMode() must be called BEFORE any Activity
 * is created. Calling it inside an Activity's onCreate() (e.g. MainActivity)
 * works too, but only if it runs before setContentView() — and even then the
 * very first Activity in a cold start can show a visible light→dark flicker.
 * Setting it here, in Application.onCreate(), avoids that flicker entirely.
 *
 * NOTE ON DARK MODE COVERAGE
 * ──────────────────────────
 * MediMind's dark theme (values-night/themes.xml) currently re-themes the
 * status bar, navigation bar, and window background. Most individual screens
 * still use hardcoded colours (e.g. @color/background_white, @color/black)
 * rather than theme attributes, so cards/text inside each screen stay in
 * their light styling even when Dark is selected. That is a deliberate,
 * non-broken "Phase 1" dark mode (dark scaffold, light content cards) — a
 * full per-screen dark palette is a separate, larger follow-up task.
 */
public class MediMindApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applySavedThemeMode();
    }

    private void applySavedThemeMode() {
        SharedPreferences prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);
        String mode = prefs.getString(Constants.KEY_THEME_MODE, Constants.THEME_LIGHT);

        if (Constants.THEME_DARK.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (Constants.THEME_SYSTEM.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        } else {
            // Default: Light. Kept as the safe default until every screen's
            // hardcoded colours are audited for full dark-mode support.
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}
