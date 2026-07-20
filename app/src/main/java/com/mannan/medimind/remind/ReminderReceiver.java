package com.mannan.medimind.remind;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;

import com.mannan.medimind.Constants;
import com.mannan.medimind.DatabaseHelper;
import com.mannan.medimind.R;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives two kinds of broadcasts:
 *   1. android.intent.action.BOOT_COMPLETED  – reschedules all active alarms.
 *   2. A medicine-reminder alarm fire         – shows the notification and
 *                                              re-schedules for the next day.
 *
 * CRITICAL FIX – ExecutorService must NOT be an instance field on a
 * BroadcastReceiver.
 *
 * Android creates a NEW BroadcastReceiver instance for EVERY broadcast.  If
 * the ExecutorService were an instance field, each broadcast would allocate a
 * new thread pool and that pool would never be shut down, producing an ever-
 * growing number of leaked threads.  The fix is:
 *
 *   • Create the ExecutorService locally inside onReceive().
 *   • Shut it down explicitly once the background task is finished.
 *   • Use goAsync() so Android does not kill the process before the task
 *     completes (the timeout is ~10 s, which is plenty for a DB read +
 *     AlarmManager call).
 *
 * The previous code already called goAsync() correctly but used an instance-
 * level executor, which was never shut down.
 */
public class ReminderReceiver extends BroadcastReceiver {

    /** Public so SettingsActivity (different package) can delete this exact channel when the user changes Sound/Vibration. */
    public static final String CHANNEL_ID = "medicine_reminder_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        // goAsync() tells Android "I'm not done yet; don't recycle the process."
        final PendingResult pendingResult = goAsync();

        // Create a fresh, single-use executor for this broadcast event.
        // It will be shut down at the end of the background task.
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                String action = intent.getAction();

                if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    // Device rebooted: re-register all active alarms
                    rescheduleAllAlarms(context);
                    return;
                }

                // Normal alarm fire: show notification + reschedule for tomorrow
                long    remindId       = intent.getLongExtra("remind_id",       -1L);
                String  medicineName   = intent.getStringExtra("medicine_name");
                boolean isNotification = intent.getBooleanExtra("is_notification", true);

                if (remindId == -1L || medicineName == null) return;

                if (isNotification) {
                    showNotification(context, medicineName, (int) remindId);
                }

                // Reschedule for the same time tomorrow (daily repeat)
                rescheduleIfActive(context, remindId);

            } finally {
                // Always release the goAsync() hold so Android can clean up
                pendingResult.finish();
                // Always shut down the one-shot executor
                executor.shutdown();
            }
        });
    }

    // ── Alarm scheduling helpers ──────────────────────────────────────────────

    /**
     * Called on BOOT_COMPLETED.  Re-creates AlarmManager entries for every
     * reminder that has isActive == true (all alarms are wiped on reboot).
     */
    private void rescheduleAllAlarms(Context context) {
        List<Remind> reminders = DatabaseHelper.getInstance(context)
                .remindDao().getAllReminders();
        if (reminders == null) return;
        for (Remind remind : reminders) {
            if (remind.isActive()) {
                scheduleAlarm(context, remind);
            }
        }
    }

    /**
     * After a reminder fires, fetch it from the DB and re-schedule for
     * tomorrow at the same time (if still active).
     */
    private void rescheduleIfActive(Context context, long remindId) {
        Remind remind = DatabaseHelper.getInstance(context)
                .remindDao().getReminderById(remindId);
        if (remind != null && remind.isActive()) {
            scheduleAlarm(context, remind);
        }
    }

    /**
     * Schedules (or re-schedules) a single exact alarm for the given reminder.
     *
     * Uses setExactAndAllowWhileIdle() on API 23+ so the alarm fires even when
     * the device is in Doze mode.
     *
     * FIX for Android 12 (API 31-32): the canScheduleExactAlarms() guard is
     * already handled by RemindFragment before the first schedule.  Here we
     * add a defensive check so a SecurityException is never thrown if the user
     * revokes the permission while the app is running.
     */
    void scheduleAlarm(Context context, Remind remind) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        // Defensive guard for Android 12 (API 31-32)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2) {
            if (!alarmManager.canScheduleExactAlarms()) return;
        }

        Intent alarmIntent = new Intent(context, ReminderReceiver.class);
        alarmIntent.putExtra("remind_id",       remind.getId());
        alarmIntent.putExtra("medicine_name",   remind.getMedicineName());
        alarmIntent.putExtra("is_alarm",        remind.isAlarm());
        alarmIntent.putExtra("is_notification", remind.isNotification());

        // FIX: FLAG_IMMUTABLE is required on API 31+ for security.
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                (int) remind.getId(),
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, remind.getHour());
        calendar.set(Calendar.MINUTE,      remind.getMinute());
        calendar.set(Calendar.SECOND,      0);
        calendar.set(Calendar.MILLISECOND, 0);

        // If the scheduled time has already passed today, push to tomorrow
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // setExactAndAllowWhileIdle fires during Doze; required for reliable reminders
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    /**
     * Reads the user's Sound / Vibration choice from Settings.
     *
     * NOTE ON ANDROID 8+ (API 26) CHANNELS: once a NotificationChannel is
     * created, its sound/vibration CANNOT be changed by the app afterwards —
     * only the user can change it via system channel settings. To make the
     * in-app Settings toggle actually take effect, SettingsActivity calls
     * NotificationManager.deleteNotificationChannel() whenever the user flips
     * either switch. That makes nm.getNotificationChannel() return null here,
     * so the channel below gets recreated fresh with the new preference.
     */
    private void showNotification(Context context, String medicineName, int notifId) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREF_NAME, Context.MODE_PRIVATE);
        boolean soundOn    = prefs.getBoolean(Constants.KEY_NOTIF_SOUND,   true);
        boolean vibrateOn  = prefs.getBoolean(Constants.KEY_NOTIF_VIBRATE, true);
        Uri     soundUri   = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        // Create the notification channel on API 26+ — only if it doesn't
        // already exist, so an existing channel's settings are never
        // silently overwritten outside of the explicit delete+recreate flow.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Medicine Reminders",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminders to take your medicines on time.");
            channel.enableVibration(vibrateOn);
            if (soundOn) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                channel.setSound(soundUri, attrs);
            } else {
                channel.setSound(null, null);
            }
            nm.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_remind)
                .setContentTitle("Medicine Reminder 💊")
                .setContentText("Time to take: " + medicineName)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        // These setSound()/setVibrate() calls only matter on pre-O devices
        // (API < 26) — on API 26+ the channel settings above take priority.
        builder.setSound(soundOn ? soundUri : null);
        builder.setVibrate(vibrateOn ? new long[]{0, 300, 200, 300} : new long[]{0});

        // Use the reminder ID as the notification ID so each medicine has its
        // own notification that can be individually dismissed.
        nm.notify(notifId, builder.build());
    }
}