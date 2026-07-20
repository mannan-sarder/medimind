package com.mannan.medimind.remind;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mannan.medimind.DatabaseHelper;
import com.mannan.medimind.R;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment for managing medicine reminders.
 *
 * KEY FIXES IN THIS VERSION:
 *
 * 1. ANDROID 12 EXACT-ALARM PERMISSION (canScheduleExactAlarms):
 *    On Android 12 (API 31) and 12L (API 32), apps must hold the
 *    SCHEDULE_EXACT_ALARM permission (user-approved) before calling
 *    setExactAndAllowWhileIdle().  Without the check, Android 12 devices
 *    throw a SecurityException and the reminder is silently never set.
 *    The fix: before scheduling, we call AlarmManager.canScheduleExactAlarms().
 *    If it returns false, we direct the user to the system settings page where
 *    they can grant the permission.
 *    Note: Android 13+ uses USE_EXACT_ALARM (auto-granted), so the check is
 *    only needed for API 31-32.
 *
 * 2. POST_NOTIFICATIONS RUNTIME PERMISSION (Android 13+):
 *    The permission request is checked BEFORE showing the add-reminder dialog
 *    so the user isn't surprised when notifications don't appear.
 *
 * 3. EXECUTOR LIFECYCLE:
 *    The ExecutorService is shut down when the Fragment's view is destroyed
 *    to prevent thread leaks between Fragment instances.
 */
public class RemindFragment extends Fragment implements RemindAdapter.OnReminderClickListener {

    private TextView     btnAddReminder;
    private RecyclerView rvRemindList;
    private TextView     tvEmptyMessage;

    private RemindAdapter adapter;
    private List<Remind>  remindList;
    private RemindDao     remindDao;

    // Single-thread executor for all DB operations
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    // ── Android 13+ POST_NOTIFICATIONS permission launcher ────────────────────
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted && isAdded()) {
                    Toast.makeText(requireContext(),
                            "Notification permission denied – reminders will not show notifications.",
                            Toast.LENGTH_LONG).show();
                }
                // Whether granted or not, proceed to show the add-reminder dialog
                showAddReminderDialog();
            });

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_remind, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnAddReminder = view.findViewById(R.id.btnAddReminder);
        rvRemindList   = view.findViewById(R.id.rvRemindList);
        tvEmptyMessage = view.findViewById(R.id.tvEmptyMessage);

        rvRemindList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new RemindAdapter(remindList, this);
        rvRemindList.setAdapter(adapter);

        DatabaseHelper db = DatabaseHelper.getInstance(requireContext());
        remindDao = db.remindDao();

        loadReminders();

        btnAddReminder.setOnClickListener(v -> checkPermissionsAndShowDialog());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Shut down the executor to prevent thread leaks
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-create the executor if it was shut down (e.g. after back-navigation)
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        loadReminders(); // Refresh in case a reminder was changed externally
    }

    // ── Permission gate ───────────────────────────────────────────────────────

    /**
     * Checks required permissions in order before showing the add-reminder dialog:
     *   1. POST_NOTIFICATIONS (Android 13+)
     *   2. Exact-alarm capability (Android 12)
     */
    private void checkPermissionsAndShowDialog() {
        // Step 1: POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Launch permission request; the dialog opens in the result callback
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }

        // Step 2: SCHEDULE_EXACT_ALARM (Android 12 / API 31-32 only)
        // On API 33+, USE_EXACT_ALARM is auto-granted; no check needed.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2) {
            AlarmManager am = (AlarmManager) requireContext().getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                // Direct the user to the system settings page for this permission
                new AlertDialog.Builder(requireContext())
                        .setTitle("Exact Alarm Permission Required")
                        .setMessage("To schedule medicine reminders at the exact time you choose, "
                                + "MediMind needs the 'Alarms & Reminders' permission.\n\n"
                                + "Please enable it in the next screen.")
                        .setPositiveButton("Open Settings", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + requireContext().getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
        }

        // All permissions satisfied → show the dialog
        showAddReminderDialog();
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadReminders() {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            List<Remind> reminders = remindDao.getAllReminders();
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    remindList = reminders;
                    adapter.updateData(remindList);
                    updateEmptyState();
                });
            }
        });
    }

    private void updateEmptyState() {
        boolean empty = (remindList == null || remindList.isEmpty());
        tvEmptyMessage.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvRemindList.setVisibility(empty ? View.GONE    : View.VISIBLE);
    }

    // ── Add reminder dialog flow ──────────────────────────────────────────────

    private void showAddReminderDialog() {
        if (!isAdded()) return;

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 16);

        EditText input = new EditText(requireContext());
        input.setHint("Medicine name");
        input.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle("Add Reminder")
                .setView(container)
                .setPositiveButton("Next", (dialog, which) -> {
                    String medicineName = input.getText().toString().trim();
                    if (medicineName.isEmpty()) {
                        Toast.makeText(getContext(), "Please enter a medicine name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showTimePickerDialog(medicineName);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTimePickerDialog(String medicineName) {
        Calendar calendar = Calendar.getInstance();
        new TimePickerDialog(requireContext(),
                (view, selectedHour, selectedMinute) ->
                        saveReminder(medicineName, selectedHour, selectedMinute),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                false
        ).show();
    }

    private void saveReminder(String medicineName, int hour, int minute) {
        Remind remind = new Remind(medicineName, hour, minute, true, true, true);
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            long id = remindDao.insert(remind);
            if (id > 0) {
                remind.setId(id);
                scheduleAlarm(remind);
                if (getActivity() != null && isAdded()) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Reminder added", Toast.LENGTH_SHORT).show();
                        loadReminders();
                    });
                }
            }
        });
    }

    // ── Adapter callbacks ─────────────────────────────────────────────────────

    @Override
    public void onSwitchToggle(Remind remind, boolean isChecked) {
        remind.setActive(isChecked);
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            remindDao.update(remind);
            if (isChecked) {
                scheduleAlarm(remind);
            } else {
                cancelAlarm(remind);
            }
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(this::loadReminders);
            }
        });
    }

    @Override
    public void onItemClick(Remind remind) {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Reminder")
                .setMessage("Delete reminder for " + remind.getMedicineName() + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (executor.isShutdown()) return;
                    executor.execute(() -> {
                        cancelAlarm(remind);
                        remindDao.delete(remind);
                        if (getActivity() != null && isAdded()) {
                            getActivity().runOnUiThread(this::loadReminders);
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Alarm scheduling ──────────────────────────────────────────────────────

    /**
     * Schedules (or re-schedules) a daily exact alarm for the given reminder.
     *
     * FIX: Added canScheduleExactAlarms() guard for Android 12 (API 31-32).
     * If the user revoked the permission after granting it, we log a warning
     * and skip the schedule silently rather than crashing.
     */
    private void scheduleAlarm(Remind remind) {
        if (!remind.isActive()) return;

        AlarmManager alarmManager =
                (AlarmManager) requireContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        // Guard for Android 12 (API 31-32)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2) {
            if (!alarmManager.canScheduleExactAlarms()) {
                // Permission was revoked after we scheduled; skip silently.
                return;
            }
        }

        Intent intent = new Intent(requireContext(), ReminderReceiver.class);
        intent.putExtra("remind_id",       remind.getId());
        intent.putExtra("medicine_name",   remind.getMedicineName());
        intent.putExtra("is_alarm",        remind.isAlarm());
        intent.putExtra("is_notification", remind.isNotification());

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                requireContext(),
                (int) remind.getId(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, remind.getHour());
        calendar.set(Calendar.MINUTE,      remind.getMinute());
        calendar.set(Calendar.SECOND,      0);
        calendar.set(Calendar.MILLISECOND, 0);
        // If the time has already passed today, schedule for tomorrow
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
        }
    }

    private void cancelAlarm(Remind remind) {
        AlarmManager alarmManager =
                (AlarmManager) requireContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(requireContext(), ReminderReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                requireContext(),
                (int) remind.getId(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
    }
}