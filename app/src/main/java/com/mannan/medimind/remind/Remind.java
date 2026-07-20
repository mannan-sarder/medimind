package com.mannan.medimind.remind;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a medicine reminder.
 * Table name: reminders
 */
@Entity(tableName = "reminders")
public class Remind {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private String medicineName;
    private int hour;          // 0-23
    private int minute;        // 0-59
    private boolean isActive;  // true = reminder enabled, false = disabled
    private boolean isAlarm;   // true = play alarm sound
    private boolean isNotification; // true = show notification

    // Empty constructor required by Room
    public Remind() {
    }

    // Parameterized constructor (without id)
    public Remind(String medicineName, int hour, int minute,
                  boolean isActive, boolean isAlarm, boolean isNotification) {
        this.medicineName = medicineName;
        this.hour = hour;
        this.minute = minute;
        this.isActive = isActive;
        this.isAlarm = isAlarm;
        this.isNotification = isNotification;
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getMedicineName() {
        return medicineName;
    }

    public void setMedicineName(String medicineName) {
        this.medicineName = medicineName;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public boolean isAlarm() {
        return isAlarm;
    }

    public void setAlarm(boolean alarm) {
        isAlarm = alarm;
    }

    public boolean isNotification() {
        return isNotification;
    }

    public void setNotification(boolean notification) {
        isNotification = notification;
    }
}