package com.mannan.medimind;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.mannan.medimind.db.MedicalReport;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReportItemAdapter extends RecyclerView.Adapter<ReportItemAdapter.ViewHolder> {

    private List<MedicalReport>      reportList;
    private final OnItemClickListener listener;

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public interface OnItemClickListener {
        void onViewClick(MedicalReport report);
        void onDeleteClick(MedicalReport report);
    }

    public ReportItemAdapter(List<MedicalReport> reportList, OnItemClickListener listener) {
        this.reportList = (reportList != null) ? reportList : new ArrayList<>();
        this.listener   = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_row, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MedicalReport report = reportList.get(position);

        // Patient name
        String name = report.getPatientName();
        holder.itemReportName.setText(
                (name != null && !name.trim().isEmpty()) ? name : "Unknown Patient");

        // Report type
        String type = report.getReportType();
        if (type == null || type.trim().isEmpty()) {
            String summary = report.getSummary();
            type = (summary != null && !summary.trim().isEmpty()) ? summary : "Medical Report";
        }
        holder.itemReportType.setText(type);

        // Date
        holder.itemDate.setText(
                (report.getDate() != null) ? dateFormat.format(report.getDate()) : "—");

        // View button
        holder.itemViewBtn.setOnClickListener(v -> {
            if (listener != null) listener.onViewClick(report);
        });
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onViewClick(report);
        });

        // Delete button
        holder.itemDeleteBtn.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteClick(report);
        });
    }

    @Override
    public int getItemCount() {
        return (reportList == null) ? 0 : reportList.size();
    }

    public void updateData(List<MedicalReport> newList) {
        final List<MedicalReport> oldList  = this.reportList;
        final List<MedicalReport> finalNew = (newList != null) ? newList : new ArrayList<>();

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return finalNew.size(); }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return oldList.get(oldPos).getId() == finalNew.get(newPos).getId();
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                MedicalReport o = oldList.get(oldPos);
                MedicalReport n = finalNew.get(newPos);
                return eq(o.getPatientName(), n.getPatientName())
                        && eq(o.getReportType(),  n.getReportType())
                        && dateEq(o, n);
            }
        });

        this.reportList = finalNew;
        diff.dispatchUpdatesTo(this);
    }

    private static boolean eq(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }

    private static boolean dateEq(MedicalReport o, MedicalReport n) {
        if (o.getDate() == null && n.getDate() == null) return true;
        if (o.getDate() == null || n.getDate() == null) return false;
        return o.getDate().equals(n.getDate());
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView itemReportName;
        TextView itemReportType;
        TextView itemDate;
        Button   itemViewBtn;
        Button   itemDeleteBtn;

        ViewHolder(@NonNull View v) {
            super(v);
            itemReportName = v.findViewById(R.id.itemReportName);
            itemReportType = v.findViewById(R.id.itemReportType);
            itemDate       = v.findViewById(R.id.itemDate);
            itemViewBtn    = v.findViewById(R.id.itemViewBtn);
            itemDeleteBtn  = v.findViewById(R.id.itemDeleteBtn);
        }
    }
}
