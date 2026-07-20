package com.mannan.medimind.remind;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface RemindDao {

    // Insert a new reminder and return its generated ID
    @Insert
    long insert(Remind remind);

    // Update an existing reminder (e.g., toggle active state)
    @Update
    void update(Remind remind);

    // Get all reminders ordered by time (hour and minute)
    @Query("SELECT * FROM reminders ORDER BY hour ASC, minute ASC")
    List<Remind> getAllReminders();

    // Get a specific reminder by its ID
    @Query("SELECT * FROM reminders WHERE id = :id")
    Remind getReminderById(long id);

    // Delete a reminder
    @Delete
    void delete(Remind remind);

    // Delete a reminder by ID (alternative)
    @Query("DELETE FROM reminders WHERE id = :id")
    void deleteById(long id);

    // Delete ALL reminders — used by Settings > "Clear All History & Reports".
    // Caller MUST cancel every reminder's AlarmManager alarm BEFORE calling
    // this (see SettingsActivity.clearAllData()), otherwise stale alarms
    // would still fire for reminders that no longer exist in the DB.
    @Query("DELETE FROM reminders")
    void deleteAllReminders();
}