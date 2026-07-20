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
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

public class DashboardActivity extends AppCompatActivity {

    private LinearLayout navHome, navAnalyze, navHistory, navCompare, navRemind;
    private ImageView iconMoreOptions;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        navHome    = findViewById(R.id.navHome);
        navAnalyze = findViewById(R.id.navAnalyze);
        navHistory = findViewById(R.id.navHistory);
        navCompare = findViewById(R.id.navCompare);
        navRemind  = findViewById(R.id.navRemind);
        iconMoreOptions = findViewById(R.id.iconMoreOptions);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        if (navController == null) {
            Toast.makeText(this, "Unable to open dashboard. Please restart the app.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        navHome.setOnClickListener(v    -> navigateToFragment(R.id.homeFragment));
        navAnalyze.setOnClickListener(v -> navigateToFragment(R.id.analyzeFragment));
        navHistory.setOnClickListener(v -> navigateToFragment(R.id.historyFragment));
        navCompare.setOnClickListener(v -> navigateToFragment(R.id.compareFragment));
        navRemind.setOnClickListener(v  -> navigateToFragment(R.id.remindFragment));

        setActiveTab(navHome);

        navController.addOnDestinationChangedListener((ctrl, dest, args) -> {
            int id = dest.getId();
            if      (id == R.id.homeFragment)    setActiveTab(navHome);
            else if (id == R.id.analyzeFragment) setActiveTab(navAnalyze);
            else if (id == R.id.historyFragment) setActiveTab(navHistory);
            else if (id == R.id.compareFragment) setActiveTab(navCompare);
            else if (id == R.id.remindFragment)  setActiveTab(navRemind);
        });

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

    private void navigateToFragment(int destinationId) {
        if (navController == null) return;
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == destinationId) return;
        navController.navigate(destinationId);
    }

    private void setActiveTab(LinearLayout activeTab) {
        resetTab(navHome);
        resetTab(navAnalyze);
        resetTab(navHistory);
        resetTab(navCompare);
        resetTab(navRemind);

        if (activeTab == null) return;
        activeTab.setSelected(true);
        // Bold the label text inside the active tab
        TextView label = getTabLabel(activeTab);
        if (label != null) label.setTypeface(null, Typeface.BOLD);
    }

    private void resetTab(LinearLayout tab) {
        if (tab == null) return;
        tab.setSelected(false);
        TextView label = getTabLabel(tab);
        if (label != null) label.setTypeface(null, Typeface.NORMAL);
    }

    /** Returns the TextView child (index 1) inside a nav LinearLayout. */
    private TextView getTabLabel(LinearLayout tab) {
        if (tab == null || tab.getChildCount() < 2) return null;
        View child = tab.getChildAt(1);
        return (child instanceof TextView) ? (TextView) child : null;
    }

    private void showMoreOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_more_options, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_profile) {
                Toast.makeText(this, "Profile (coming soon)", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_settings) {
                Toast.makeText(this, "Settings (coming soon)", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_logout) {
                handleLogout();
                return true;
            } else if (id == R.id.action_debug_ocr) {
                shareLastOcrText();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * Debug aid: shares the raw ML-Kit OCR text captured from the most recent
     * scan (saved by AnalyzeFragment to SharedPreferences right after OCR
     * succeeds, before any parsing happens). Lets a developer grab the exact
     * recognised text — instead of guessing from the photo — when a scan's
     * result looks wrong. A running history of every scan's raw text is also
     * kept as timestamped files under filesDir/ocr_debug/ for deeper digging
     * (visible via a file manager or `adb shell run-as <package> ls files/ocr_debug`).
     */
    private void shareLastOcrText() {
        String rawText = getSharedPreferences("MediMindPrefs", MODE_PRIVATE)
                .getString("last_ocr_raw_text", null);
        if (rawText == null || rawText.trim().isEmpty()) {
            Toast.makeText(this,
                    "No OCR text captured yet — scan a report first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "MediMind — Last OCR Raw Text");
        shareIntent.putExtra(Intent.EXTRA_TEXT, rawText);
        startActivity(Intent.createChooser(shareIntent, "Share raw OCR text"));
    }

    private void handleLogout() {
        SharedPreferences prefs = getSharedPreferences("MediMindPrefs", MODE_PRIVATE);
        prefs.edit().clear().apply();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void applyLightStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
    }
}
