package com.mannan.medimind;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.mannan.medimind.Constants;
import com.mannan.medimind.R;

public class HomeFragment extends Fragment {

    private ImageButton btnAddReport;
    private TextView tvAppTitle;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize views
        btnAddReport = view.findViewById(R.id.btnAddReport);
        tvAppTitle = view.findViewById(R.id.tvAppTitle);

        // Optional: Display user name from SharedPreferences
        displayUserName();

        // Set click listener to navigate to AnalyzeFragment
        btnAddReport.setOnClickListener(v -> {
            // Add a pulse animation to the button (optional)
            Animation pulse = AnimationUtils.loadAnimation(requireContext(), R.anim.pulse);
            v.startAnimation(pulse);

            // Navigate to AnalyzeFragment using the action defined in nav_graph.xml
            // Make sure the action ID matches your navigation graph
            Navigation.findNavController(v).navigate(R.id.action_homeFragment_to_analyzeFragment);
        });
    }

    /**
     * Optional: Load user name from SharedPreferences and set it on the title TextView.
     * If no user name is found, falls back to the default "Welcome to MediMind".
     */
    private void displayUserName() {
        SharedPreferences prefs = requireActivity().getSharedPreferences(Constants.PREF_NAME, 0);
        String userName = prefs.getString(Constants.KEY_USER_NAME, null);
        if (userName != null && !userName.isEmpty()) {
            tvAppTitle.setText(getString(R.string.welcome_user, userName));
        } else {
            // Keep the default text from XML
            tvAppTitle.setText(R.string.welcome_medimind);
        }
    }
}