package com.mannan.medimind.remind;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.mannan.medimind.R;

import java.util.List;

/**
 * RecyclerView adapter for the medicine-reminder list.
 *
 * KEY FIX – Deprecated widget replacement:
 *
 * android.widget.Switch was deprecated in API 29 (Android 10).  The
 * replacement for backward-compatible apps is:
 *
 *   androidx.appcompat.widget.SwitchCompat    (AppCompat; no Material theme needed)
 *   com.google.android.material.switchmaterial.SwitchMaterial  (Material Design 3)
 *
 * This file uses SwitchCompat because the project already depends on AppCompat
 * and does not necessarily use the full Material3 theme.  If you migrate the
 * entire app to Material3, swap the import and the XML attribute for
 * SwitchMaterial instead.
 *
 * The layout file (item_remind.xml) must also be updated to reference
 *   <androidx.appcompat.widget.SwitchCompat …/>
 * instead of <Switch …/>.
 */
public class RemindAdapter extends RecyclerView.Adapter<RemindAdapter.RemindViewHolder> {

    private List<Remind>            reminderList;
    private final OnReminderClickListener listener;

    public interface OnReminderClickListener {
        void onSwitchToggle(Remind remind, boolean isChecked);
        void onItemClick(Remind remind);
    }

    public RemindAdapter(List<Remind> reminderList, OnReminderClickListener listener) {
        this.reminderList = reminderList;
        this.listener     = listener;
    }

    @NonNull
    @Override
    public RemindViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_remind, parent, false);
        return new RemindViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RemindViewHolder holder, int position) {
        Remind remind = reminderList.get(position);

        holder.tvMedicineName.setText(remind.getMedicineName());
        holder.tvTime.setText(formatTime(remind.getHour(), remind.getMinute()));

        // Clear the listener before setting the checked state to prevent
        // spurious callbacks while the ViewHolder is being recycled / rebound.
        holder.switchActive.setOnCheckedChangeListener(null);
        holder.switchActive.setChecked(remind.isActive());
        holder.switchActive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) listener.onSwitchToggle(remind, isChecked);
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(remind);
        });
    }

    @Override
    public int getItemCount() {
        return (reminderList == null) ? 0 : reminderList.size();
    }

    /**
     * Replaces the current list and redraws the RecyclerView.
     * Should only be called from the UI thread.
     */
    public void updateData(List<Remind> newList) {
        this.reminderList = newList;
        notifyDataSetChanged();
    }

    // ── Time formatting ───────────────────────────────────────────────────────

    /**
     * Converts 24-hour values into a human-readable "08:30 AM" string.
     * Avoids locale-sensitive SimpleDateFormat so the output is predictable.
     */
    private String formatTime(int hour, int minute) {
        int    displayHour;
        String amPm;
        if (hour == 0) {
            displayHour = 12; amPm = "AM";
        } else if (hour < 12) {
            displayHour = hour; amPm = "AM";
        } else if (hour == 12) {
            displayHour = 12; amPm = "PM";
        } else {
            displayHour = hour - 12; amPm = "PM";
        }
        return String.format("%02d:%02d %s", displayHour, minute, amPm);
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class RemindViewHolder extends RecyclerView.ViewHolder {
        TextView     tvTime, tvMedicineName;
        SwitchCompat switchActive; // FIX: was android.widget.Switch (deprecated API 29)

        RemindViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTime         = itemView.findViewById(R.id.tvTime);
            tvMedicineName = itemView.findViewById(R.id.tvMedicineName);
            switchActive   = itemView.findViewById(R.id.switchActive);
        }
    }
}