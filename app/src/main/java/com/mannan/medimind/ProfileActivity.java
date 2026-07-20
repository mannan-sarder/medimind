package com.mannan.medimind;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;

/**
 * Profile screen — reached from MainActivity's 3-dot overflow menu.
 *
 * Lets the user view, edit, and save their personal info (the same fields
 * collected at Signup), or reset the profile back to empty without logging
 * out. All data is local-only (UserManager / SharedPreferences) — there is
 * no backend, so "Save" simply persists to SharedPreferences.
 */
public class ProfileActivity extends AppCompatActivity {

    private EditText editTextFullName, editTextEmail, editTextPhone, editTextAge, editTextAddress;
    private Spinner  spinnerGender;
    private AppCompatButton buttonSave;
    private TextView buttonResetProfile;

    private UserManager userManager;
    private String selectedGender = Constants.GENDER_MALE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        userManager = UserManager.getInstance(this);

        editTextFullName   = findViewById(R.id.editTextFullName);
        editTextEmail       = findViewById(R.id.editTextEmail);
        editTextPhone        = findViewById(R.id.editTextPhone);
        editTextAge          = findViewById(R.id.editTextAge);
        editTextAddress      = findViewById(R.id.editTextAddress);
        spinnerGender        = findViewById(R.id.spinnerGender);
        buttonSave           = findViewById(R.id.buttonSave);
        buttonResetProfile   = findViewById(R.id.buttonResetProfile);

        TextView textViewBack = findViewById(R.id.textViewBack);
        textViewBack.setOnClickListener(v -> finish());

        setupGenderSpinner();
        prefillExistingProfile();

        buttonSave.setOnClickListener(v -> saveProfile());
        buttonResetProfile.setOnClickListener(v -> confirmResetProfile());
    }

    private void setupGenderSpinner() {
        String[] genders = {Constants.GENDER_MALE, Constants.GENDER_FEMALE, Constants.GENDER_ANY};
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, genders);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGender.setAdapter(adapter);
        spinnerGender.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedGender = genders[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedGender = Constants.GENDER_MALE;
            }
        });
    }

    /** Fills the form with the currently saved profile, if one exists. */
    private void prefillExistingProfile() {
        User user = userManager.getCurrentUser();
        if (user == null) return; // No profile saved yet — leave fields empty.

        editTextFullName.setText(user.getName());
        editTextEmail.setText(user.getEmail());
        editTextPhone.setText(user.getPhone());
        if (user.getAge() > 0) {
            editTextAge.setText(String.valueOf(user.getAge()));
        }
        editTextAddress.setText(user.getAddress());

        String gender = user.getGender();
        if (gender != null) {
            int position = gender.equals(Constants.GENDER_FEMALE) ? 1
                    : gender.equals(Constants.GENDER_ANY) ? 2 : 0;
            spinnerGender.setSelection(position);
        }
    }

    private void saveProfile() {
        String name    = editTextFullName.getText().toString().trim();
        String email   = editTextEmail.getText().toString().trim();
        String phone   = editTextPhone.getText().toString().trim();
        String address = editTextAddress.getText().toString().trim();
        String ageStr  = editTextAge.getText().toString().trim();

        if (name.isEmpty()) {
            editTextFullName.setError("Name is required");
            editTextFullName.requestFocus();
            return;
        }

        int age = 0;
        if (!ageStr.isEmpty()) {
            try {
                age = Integer.parseInt(ageStr);
            } catch (NumberFormatException e) {
                editTextAge.setError("Enter a valid age");
                editTextAge.requestFocus();
                return;
            }
        }

        User user = new User(name, email, phone, selectedGender, age, address);
        userManager.saveUser(user);

        Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmResetProfile() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Profile?")
                .setMessage("This clears your name, email, phone, age, gender, and address. " +
                        "Your saved reports and reminders are NOT deleted, and you will stay logged in.")
                .setPositiveButton("Reset", (dialog, which) -> {
                    userManager.clearProfile();
                    Toast.makeText(this, "Profile reset", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
