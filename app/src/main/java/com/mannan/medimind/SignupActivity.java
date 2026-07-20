package com.mannan.medimind;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class SignupActivity extends AppCompatActivity {

    // Views
    private TextView textViewBack, textViewHaveAccount;
    private EditText editTextFullName, editTextEmail, editTextPhone, editTextDob, editTextAddress, editTextPassword, editTextConfirmPassword;
    private Spinner spinnerGender;
    private Button buttonSignup;

    // Data
    private String selectedGender = Constants.GENDER_MALE;
    private int userAge = 25; // default adult age

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        // Initialize views
        textViewBack = findViewById(R.id.textViewBack);
        textViewHaveAccount = findViewById(R.id.textViewHaveAccount);
        editTextFullName = findViewById(R.id.editTextFullName);
        editTextEmail = findViewById(R.id.editTextEmail);
        editTextPhone = findViewById(R.id.editTextPhone);
        editTextDob = findViewById(R.id.editTextDob);
        editTextAddress = findViewById(R.id.editTextAddress);
        editTextPassword = findViewById(R.id.editTextPassword);
        editTextConfirmPassword = findViewById(R.id.editTextConfirmPassword);
        spinnerGender = findViewById(R.id.spinnerGender);
        buttonSignup = findViewById(R.id.buttonSignup);

        // Setup Gender Spinner
        setupGenderSpinner();

        // Setup Date of Birth picker
        setupDateOfBirthPicker();

        // Back button (top right)
        textViewBack.setOnClickListener(v -> finish());

        // "Already have account" text
        textViewHaveAccount.setOnClickListener(v -> finish());

        // Sign up button click
        buttonSignup.setOnClickListener(v -> attemptSignup());
    }

    private void setupGenderSpinner() {
        String[] genders = {Constants.GENDER_MALE, Constants.GENDER_FEMALE, Constants.GENDER_ANY};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, genders);
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

    private void setupDateOfBirthPicker() {
        editTextDob.setOnClickListener(v -> showDatePickerDialog());
        editTextDob.setFocusable(false);
        editTextDob.setClickable(true);
    }

    private void showDatePickerDialog() {
        final Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH);
        int day = calendar.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog datePickerDialog = new DatePickerDialog(this,
                (view, selectedYear, selectedMonth, selectedDay) -> {
                    String dateStr = selectedYear + "-" + (selectedMonth + 1) + "-" + selectedDay;
                    editTextDob.setText(dateStr);
                    // Calculate age
                    userAge = calculateAge(selectedYear, selectedMonth + 1, selectedDay);
                }, year, month, day);
        datePickerDialog.show();
    }

    private int calculateAge(int year, int month, int day) {
        Calendar dob = Calendar.getInstance();
        dob.set(year, month - 1, day);
        Calendar today = Calendar.getInstance();
        int age = today.get(Calendar.YEAR) - dob.get(Calendar.YEAR);
        if (today.get(Calendar.DAY_OF_YEAR) < dob.get(Calendar.DAY_OF_YEAR)) {
            age--;
        }
        return Math.max(age, 0);
    }

    private void attemptSignup() {
        String fullName = editTextFullName.getText().toString().trim();
        String email = editTextEmail.getText().toString().trim();
        String phone = editTextPhone.getText().toString().trim();
        String dob = editTextDob.getText().toString().trim();
        String address = editTextAddress.getText().toString().trim();
        String password = editTextPassword.getText().toString().trim();
        String confirmPassword = editTextConfirmPassword.getText().toString().trim();

        // Validation
        if (TextUtils.isEmpty(fullName)) {
            editTextFullName.setError("Full name required");
            editTextFullName.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(email)) {
            editTextEmail.setError("Email required");
            editTextEmail.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            editTextEmail.setError("Enter valid email");
            editTextEmail.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(phone)) {
            editTextPhone.setError("Phone number required");
            editTextPhone.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(dob)) {
            editTextDob.setError("Date of birth required");
            editTextDob.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(address)) {
            editTextAddress.setError("Address required");
            editTextAddress.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(password)) {
            editTextPassword.setError("Password required");
            editTextPassword.requestFocus();
            return;
        }
        if (password.length() < 6) {
            editTextPassword.setError("Password must be at least 6 characters");
            editTextPassword.requestFocus();
            return;
        }
        if (!password.equals(confirmPassword)) {
            editTextConfirmPassword.setError("Passwords do not match");
            editTextConfirmPassword.requestFocus();
            return;
        }

        // Save user data to SharedPreferences
        SharedPreferences prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(Constants.KEY_IS_LOGGED_IN, true);
        editor.putString(Constants.KEY_USER_NAME, fullName);
        editor.putString(Constants.KEY_USER_EMAIL, email);
        editor.putString(Constants.KEY_USER_PHONE, phone);
        editor.putString(Constants.KEY_USER_ADDRESS, address);
        editor.putString(Constants.KEY_USER_GENDER, selectedGender);
        editor.putInt(Constants.KEY_USER_AGE, userAge);
        editor.apply();

        Toast.makeText(this, "Signup successful!", Toast.LENGTH_SHORT).show();

        // Navigate to Dashboard (MainActivity)
        Intent intent = new Intent(SignupActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}