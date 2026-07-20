package com.mannan.medimind;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

public class MainActivity extends AppCompatActivity {

    private LinearLayout navHome, navAnalyze, navHistory, navCompare, navRemind;
    private ImageView iconMoreOptions;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Login gate
        SharedPreferences prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(Constants.KEY_IS_LOGGED_IN, false)) {
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        setContentView(R.layout.activity_dashboard);

        navHome         = findViewById(R.id.navHome);
        navAnalyze      = findViewById(R.id.navAnalyze);
        navHistory      = findViewById(R.id.navHistory);
        navCompare      = findViewById(R.id.navCompare);
        navRemind       = findViewById(R.id.navRemind);
        iconMoreOptions = findViewById(R.id.iconMoreOptions);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }
        if (navController == null) {
            Toast.makeText(this, "Navigation error – please restart the app.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        navHome.setOnClickListener(v    -> navigateTo(R.id.homeFragment));
        navAnalyze.setOnClickListener(v -> navigateTo(R.id.analyzeFragment));
        navHistory.setOnClickListener(v -> navigateTo(R.id.historyFragment));
        navCompare.setOnClickListener(v -> navigateTo(R.id.compareFragment));
        navRemind.setOnClickListener(v  -> navigateTo(R.id.remindFragment));

        navController.addOnDestinationChangedListener((ctrl, dest, args) -> {
            int id = dest.getId();
            if      (id == R.id.homeFragment)    setActiveTab(navHome);
            else if (id == R.id.analyzeFragment) setActiveTab(navAnalyze);
            else if (id == R.id.historyFragment) setActiveTab(navHistory);
            else if (id == R.id.compareFragment) setActiveTab(navCompare);
            else if (id == R.id.remindFragment)  setActiveTab(navRemind);
        });
        setActiveTab(navHome);

        iconMoreOptions.setOnClickListener(this::showMoreOptionsMenu);
        applyLightStatusBar();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!navController.popBackStack()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void navigateTo(int destinationId) {
        if (navController == null) return;
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == destinationId) return;
        navController.navigate(destinationId);
    }

    private void setActiveTab(LinearLayout activeTab) {
        resetTabStyle(navHome);
        resetTabStyle(navAnalyze);
        resetTabStyle(navHistory);
        resetTabStyle(navCompare);
        resetTabStyle(navRemind);

        if (activeTab == null) return;
        activeTab.setSelected(true);
        TextView label = getTabLabel(activeTab);
        if (label != null) label.setTypeface(null, Typeface.BOLD);
    }

    private void resetTabStyle(LinearLayout tab) {
        if (tab == null) return;
        tab.setSelected(false);
        TextView label = getTabLabel(tab);
        if (label != null) label.setTypeface(null, Typeface.NORMAL);
    }

    private TextView getTabLabel(LinearLayout tab) {
        if (tab == null || tab.getChildCount() < 2) return null;
        View child = tab.getChildAt(1);
        return (child instanceof TextView) ? (TextView) child : null;
    }

    private void showMoreOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_more_options, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_profile) {
                startActivity(new Intent(this, ProfileActivity.class));
                return true;
            } else if (itemId == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            } else if (itemId == R.id.action_logout) {
                logout();
                return true;
            } else if (itemId == R.id.action_debug_ocr) {
                shareLastOcrText();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * Shares the raw ML Kit OCR text saved by AnalyzeFragment after the most
     * recent scan. Useful for diagnosing parser bugs from the actual recognised
     * text rather than guessing from the photo.
     */
    private void shareLastOcrText() {
        String rawText = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE)
                .getString("last_ocr_raw_text", null);
        if (rawText == null || rawText.trim().isEmpty()) {
            Toast.makeText(this,
                    "No OCR text captured yet — scan a report first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent shareIntent = new android.content.Intent(
                android.content.Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
                "MediMind — Last OCR Raw Text");
        shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, rawText);
        startActivity(android.content.Intent.createChooser(
                shareIntent, "Share raw OCR text"));
    }

    private void logout() {
        // FIX: Previously only set KEY_IS_LOGGED_IN = false, leaving all profile
        // keys intact. After logout → Skip, UserManager.getCurrentUser() would
        // still find KEY_USER_EMAIL in prefs and return the old profile.
        // Now we call UserManager.logout() which does prefs.clear() — wiping
        // every key including name, email, phone, gender, age, address.
        UserManager.getInstance(this).logout();
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /**
     * Sets the status-bar colour/icon appearance to match the current
     * day/night mode.
     *
     * FIX: This used to unconditionally force a white status bar with dark
     * icons, which looked fine in light mode but fought against the new
     * dark theme (values-night/themes.xml) — it would paint a white bar over
     * what should be a dark one whenever Settings > Theme = Dark/System-dark
     * was active. Now it reads the current night-mode config and picks the
     * matching colour + icon style instead of hardcoding the light variant.
     */
    private void applyLightStatusBar() {
        boolean isNightMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        int barColor = isNightMode
                ? ContextCompat.getColor(this, R.color.background_dark)
                : ContextCompat.getColor(this, android.R.color.white);

        getWindow().setStatusBarColor(barColor);
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        // Light status bar (dark icons) only makes sense on a light bar.
        insetsController.setAppearanceLightStatusBars(!isNightMode);
    }

    @Override
    public boolean onSupportNavigateUp() {
        return (navController != null && navController.navigateUp())
                || super.onSupportNavigateUp();
    }
}
