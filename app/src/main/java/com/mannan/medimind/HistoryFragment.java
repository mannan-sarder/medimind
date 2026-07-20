package com.mannan.medimind;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mannan.medimind.db.MedicalReport;
import com.mannan.medimind.db.ReportDao;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryFragment extends Fragment {

    private RecyclerView        rvReportList;
    private Button              btnPrev, btnNext;
    private TextView            tvPageNumber;

    private ReportItemAdapter   adapter;
    private SharedViewModel     sharedViewModel;

    private int currentPage = 1;
    private int totalPages  = 1;
    private final int pageSize = Constants.DEFAULT_PAGE_SIZE;

    private ExecutorService executor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        executor = Executors.newSingleThreadExecutor();

        rvReportList  = view.findViewById(R.id.rvReportList);
        btnPrev       = view.findViewById(R.id.btnPrev);
        btnNext       = view.findViewById(R.id.btnNext);
        tvPageNumber  = view.findViewById(R.id.tvPageNumber);

        sharedViewModel = new ViewModelProvider(requireActivity())
                .get(SharedViewModel.class);

        rvReportList.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new ReportItemAdapter(new ArrayList<>(), new ReportItemAdapter.OnItemClickListener() {
            @Override
            public void onViewClick(MedicalReport report) {
                if (report != null && isAdded()) {
                    sharedViewModel.setSelectedReportId(report.getId());
                    Navigation.findNavController(view)
                            .navigate(R.id.action_historyFragment_to_resultFragment);
                }
            }

            @Override
            public void onDeleteClick(MedicalReport report) {
                if (report == null || !isAdded()) return;
                // Confirm dialog before deleting
                new AlertDialog.Builder(requireContext())
                        .setTitle("Delete Report")
                        .setMessage("Are you sure you want to delete this report?")
                        .setPositiveButton("Delete", (dialog, which) -> deleteReport(report))
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        rvReportList.setAdapter(adapter);

        btnPrev.setOnClickListener(v -> goToPreviousPage());
        btnNext.setOnClickListener(v -> goToNextPage());

        loadReportsForPage(currentPage);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadReportsForPage(currentPage);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────

    private void deleteReport(MedicalReport report) {
        if (executor == null || executor.isShutdown()) return;

        executor.execute(() -> {
            DatabaseHelper db  = DatabaseHelper.getInstance(requireContext());
            ReportDao      dao = db.reportDao();
            dao.deleteReport(report);

            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Report deleted", Toast.LENGTH_SHORT).show();
                    // If current page becomes empty after delete, go back one page
                    int remaining = adapter.getItemCount() - 1;
                    if (remaining <= 0 && currentPage > 1) {
                        currentPage--;
                    }
                    loadReportsForPage(currentPage);
                });
            }
        });
    }

    // ── Pagination ────────────────────────────────────────────────────────

    private void goToPreviousPage() {
        if (currentPage > 1) { currentPage--; loadReportsForPage(currentPage); }
    }

    private void goToNextPage() {
        if (currentPage < totalPages) { currentPage++; loadReportsForPage(currentPage); }
    }

    private void loadReportsForPage(int page) {
        if (executor == null || executor.isShutdown()) return;

        btnPrev.setEnabled(false);
        btnNext.setEnabled(false);

        executor.execute(() -> {
            DatabaseHelper db       = DatabaseHelper.getInstance(requireContext());
            ReportDao      dao      = db.reportDao();
            int            offset   = (page - 1) * pageSize;

            List<MedicalReport> reports = dao.getReportsPaginated(pageSize, offset);
            int                 total   = dao.getTotalReportsCount();
            int                 pages   = (total == 0) ? 1
                    : (int) Math.ceil((double) total / pageSize);

            final int                 finalPages   = pages;
            final List<MedicalReport> finalReports =
                    (reports != null) ? reports : new ArrayList<>();

            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    adapter.updateData(finalReports);

                    totalPages = finalPages;
                    tvPageNumber.setText(
                            getString(R.string.page_format, currentPage, totalPages));

                    btnPrev.setEnabled(currentPage > 1);
                    btnNext.setEnabled(currentPage < totalPages);

                    if (finalReports.isEmpty()) {
                        Toast.makeText(getContext(),
                                "No reports found. Please scan a report first.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }
}
