package com.mannan.medimind;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;

import com.mannan.medimind.remind.Remind;
import com.mannan.medimind.remind.ReminderReceiver;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings screen — reached from MainActivity's 3-dot overflow menu.
 *
 * All preferences are stored under Constants.PREF_NAME via SharedPreferences,
 * the same store UserManager and the reminder system already use.
 */
public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    private SwitchCompat switchSound, switchVibration;
    private RadioGroup    radioGroupTheme, radioGroupLanguage;
    private TextView      buttonClearData, textAppVersion;

    // Single-thread executor for the "clear all data" DB + alarm work.
    // Shut down in onDestroy() to avoid leaking it past this Activity's life.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);

        TextView textViewBack = findViewById(R.id.textViewBack);
        textViewBack.setOnClickListener(v -> finish());

        switchSound        = findViewById(R.id.switchSound);
        switchVibration     = findViewById(R.id.switchVibration);
        radioGroupTheme      = findViewById(R.id.radioGroupTheme);
        radioGroupLanguage   = findViewById(R.id.radioGroupLanguage);
        buttonClearData      = findViewById(R.id.buttonClearData);
        textAppVersion       = findViewById(R.id.textAppVersion);

        setupReminderSwitches();
        setupThemeSelector();
        setupLanguageSelector();
        buttonClearData.setOnClickListener(v -> confirmClearAllData());

        showAppVersion();
    }

    // ── Reminders: Sound / Vibration ────────────────────────────────────────

    private void setupReminderSwitches() {
        switchSound.setChecked(prefs.getBoolean(Constants.KEY_NOTIF_SOUND, true));
        switchVibration.setChecked(prefs.getBoolean(Constants.KEY_NOTIF_VIBRATE, true));

        switchSound.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(Constants.KEY_NOTIF_SOUND, checked).apply();
            forceNotificationChannelRebuild();
        });
        switchVibration.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(Constants.KEY_NOTIF_VIBRATE, checked).apply();
            forceNotificationChannelRebuild();
        });
    }

    /**
     * On Android 8+ (API 26), a NotificationChannel's sound/vibration is
     * fixed once created — the app can't change it later. Deleting the
     * channel here makes ReminderReceiver.showNotification() recreate it
     * fresh (with the new Sound/Vibration prefs applied) the next time a
     * reminder fires. Pre-O devices don't have channels, so this is a no-op
     * there — the prefs are read directly per-notification instead.
     */
    private void forceNotificationChannelRebuild() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.deleteNotificationChannel(ReminderReceiver.CHANNEL_ID);
        }
    }

    // ── Appearance: Theme ────────────────────────────────────────────────────

    private void setupThemeSelector() {
        String mode = prefs.getString(Constants.KEY_THEME_MODE, Constants.THEME_LIGHT);
        if (Constants.THEME_DARK.equals(mode)) {
            radioGroupTheme.check(R.id.radioThemeDark);
        } else if (Constants.THEME_SYSTEM.equals(mode)) {
            radioGroupTheme.check(R.id.radioThemeSystem);
        } else {
            radioGroupTheme.check(R.id.radioThemeLight);
        }

        radioGroupTheme.setOnCheckedChangeListener((group, checkedId) -> {
            String newMode;
            int nightMode;
            if (checkedId == R.id.radioThemeDark) {
                newMode = Constants.THEME_DARK;
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else if (checkedId == R.id.radioThemeSystem) {
                newMode = Constants.THEME_SYSTEM;
                nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            } else {
                newMode = Constants.THEME_LIGHT;
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
            }
            prefs.edit().putString(Constants.KEY_THEME_MODE, newMode).apply();
            // Applies immediately — AppCompat recreates this Activity to
            // pick up the new night-mode resources right away.
            AppCompatDelegate.setDefaultNightMode(nightMode);
        });
    }

    // ── Report language default ─────────────────────────────────────────────

    private void setupLanguageSelector() {
        String lang = prefs.getString(Constants.KEY_DISPLAY_LANGUAGE, Constants.LANG_BN);
        radioGroupLanguage.check(
                Constants.LANG_EN.equals(lang) ? R.id.radioLangEn : R.id.radioLangBn);

        radioGroupLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            String newLang = (checkedId == R.id.radioLangEn) ? Constants.LANG_EN : Constants.LANG_BN;
            prefs.edit().putString(Constants.KEY_DISPLAY_LANGUAGE, newLang).apply();
        });
    }

    // ── Data: Clear all history & reports ───────────────────────────────────

    private void confirmClearAllData() {
        new AlertDialog.Builder(this)
                .setTitle("Clear All History & Reports?")
                .setMessage("This permanently deletes every saved report, test result, and " +
                        "reminder on this device. This cannot be undone.")
                .setPositiveButton("Delete Everything", (dialog, which) -> clearAllData())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearAllData() {
        Context appContext = getApplicationContext();
        executor.execute(() -> {
            DatabaseHelper db = DatabaseHelper.getInstance(appContext);

            // 1. Cancel every scheduled alarm BEFORE deleting the reminder
            //    rows — otherwise a stale alarm could still fire for a
            //    reminder ID that no longer exists in the DB.
            List<Remind> reminders = db.remindDao().getAllReminders();
            AlarmManager alarmManager =
                    (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null && reminders != null) {
                for (Remind remind : reminders) {
                    Intent intent = new Intent(appContext, ReminderReceiver.class);
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                            appContext,
                            (int) remind.getId(),
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    alarmManager.cancel(pendingIntent);
                    pendingIntent.cancel();
                }
            }

            // 2. Wipe the tables. No FK cascade between reports and
            //    test_results, so both must be cleared explicitly.
            db.remindDao().deleteAllReminders();
            db.testResultDao().deleteAllTestResults();
            db.reportDao().deleteAllReports();

            runOnUiThread(() ->
                    Toast.makeText(this, "All history and reports cleared", Toast.LENGTH_SHORT).show());
        });
    }

    // ── About ────────────────────────────────────────────────────────────────

    private void showAppVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            textAppVersion.setText("MediMind v" + info.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            // Keep the layout's static fallback text if this ever fails.
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
