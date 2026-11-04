package com.punch.app.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.punch.app.R;
import com.punch.app.adapter.ClockStatisticsAdapter;
import com.punch.app.adapter.PunchRecordAdapter;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.PunchRecord;
import com.punch.app.network.ApiResult;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.utils.LifecycleRequestGate;
import com.punch.app.utils.SessionManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RecordsFragment extends Fragment {
    private static final int PAGE_SIZE = 20;
    private static final String UNSCHEDULED_PUNCH_OPTION_LABEL = "\u672a\u914d\u7f6e\u73ed\u6b21";

    private ToggleButton toggleMode;
    private TextView tvDate;
    private TextView tvTotalCount;
    private TextView tvTotalLabel;
    private TextView tvClockedCount;
    private TextView tvClockedLabel;
    private TextView tvUnclockedCount;
    private TextView tvUnclockedLabel;
    private TextView tvSpecialCount;
    private TextView tvSpecialLabel;
    private View viewSpecialGap;
    private View layoutSpecialCard;
    private View layoutRecordFilters;
    private Spinner spinnerPunchIndex;
    private Spinner spinnerStatus;
    private RecyclerView recyclerView;

    private PunchRecordAdapter localAdapter;
    private ClockStatisticsAdapter onlineAdapter;
    private ArrayAdapter<String> punchOptionAdapter;
    private ArrayAdapter<String> statusAdapter;
    private final List<PunchOption> punchOptions = new ArrayList<>();
    private final List<StatusOption> statusOptions = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final LifecycleRequestGate viewGate = new LifecycleRequestGate();
    private final StatisticsRequestCoordinator requestCoordinator =
            new StatisticsRequestCoordinator();
    private Future<?> currentLoadTask;
    private int viewToken;

    private boolean suppressSelectionCallback;
    private boolean loading;
    private boolean onlineMode;
    private boolean preferOnlineMode;
    private boolean panelSelected;
    private boolean controlsInitialized;
    private boolean hasMore;
    private int currentPage;
    private PunchOption selectedPunchOption;
    private StatusOption selectedStatusOption;
    private String selectedDate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_records, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewToken = viewGate.open();
        controlsInitialized = false;

        toggleMode = view.findViewById(R.id.toggle_mode);
        tvDate = view.findViewById(R.id.tv_date);
        tvTotalCount = view.findViewById(R.id.tv_total_count);
        tvTotalLabel = view.findViewById(R.id.tv_total_label);
        tvClockedCount = view.findViewById(R.id.tv_clocked_count);
        tvClockedLabel = view.findViewById(R.id.tv_clocked_label);
        tvUnclockedCount = view.findViewById(R.id.tv_unclocked_count);
        tvUnclockedLabel = view.findViewById(R.id.tv_unclocked_label);
        tvSpecialCount = view.findViewById(R.id.tv_special_count);
        tvSpecialLabel = view.findViewById(R.id.tv_special_label);
        viewSpecialGap = view.findViewById(R.id.view_special_gap);
        layoutSpecialCard = view.findViewById(R.id.layout_special_card);
        layoutRecordFilters = view.findViewById(R.id.layout_record_filters);
        spinnerPunchIndex = view.findViewById(R.id.spinner_punch_index);
        spinnerStatus = view.findViewById(R.id.spinner_status);
        recyclerView = view.findViewById(R.id.recycler_view);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        localAdapter = new PunchRecordAdapter(requireContext(), new ArrayList<>());
        onlineAdapter = new ClockStatisticsAdapter();

        selectedDate = buildToday();
        tvDate.setText(selectedDate);
        tvDate.setOnClickListener(v -> showDatePicker());

        preferOnlineMode = SessionManager.get().isTokenValid();
        toggleMode.setChecked(preferOnlineMode);
        toggleMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (preferOnlineMode == isChecked) {
                return;
            }
            preferOnlineMode = isChecked;
            rebuildPunchOptions();
            requestRefresh(StatisticsRequestCoordinator.Reason.FILTER_CHANGED);
        });

        punchOptionAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<>()
        );
        spinnerPunchIndex.setAdapter(punchOptionAdapter);
        spinnerPunchIndex.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                if (suppressSelectionCallback || position < 0 || position >= punchOptions.size()) {
                    return;
                }
                PunchOption option = punchOptions.get(position);
                if (!controlsInitialized || samePunchOption(selectedPunchOption, option)) {
                    return;
                }
                selectedPunchOption = option;
                requestRefresh(StatisticsRequestCoordinator.Reason.FILTER_CHANGED);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        statusAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<>()
        );
        spinnerStatus.setAdapter(statusAdapter);
        spinnerStatus.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                if (suppressSelectionCallback || position < 0 || position >= statusOptions.size()) {
                    return;
                }
                StatusOption option = statusOptions.get(position);
                if (!controlsInitialized || sameStatusOption(selectedStatusOption, option)) {
                    return;
                }
                selectedStatusOption = option;
                requestRefresh(StatisticsRequestCoordinator.Reason.FILTER_CHANGED);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0 || !onlineMode || loading || !hasMore) {
                    return;
                }
                RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
                if (!(layoutManager instanceof LinearLayoutManager)) {
                    return;
                }
                LinearLayoutManager linear = (LinearLayoutManager) layoutManager;
                int lastVisible = linear.findLastVisibleItemPosition();
                if (lastVisible >= Math.max(0, onlineAdapter.getItemCount() - 4)) {
                    requestStatistics(StatisticsRequestCoordinator.Reason.NEXT_PAGE, false);
                }
            }
        });

        rebuildPunchOptions();
        rebuildStatusOptions();
        controlsInitialized = true;
        if (panelSelected) {
            requestRefresh(StatisticsRequestCoordinator.Reason.PANEL_READY);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        controlsInitialized = false;
        rebuildPunchOptions();
        controlsInitialized = true;
    }

    public void refresh() {
        requestRefresh(StatisticsRequestCoordinator.Reason.USER_REFRESH);
    }

    public void onPanelEntered() {
        panelSelected = true;
        if (isAdded() && getView() != null && controlsInitialized) {
            requestRefresh(StatisticsRequestCoordinator.Reason.PANEL_ENTER);
        }
    }

    public void onPanelExited() {
        panelSelected = false;
        cancelCurrentLoad();
        requestCoordinator.invalidate();
        loading = false;
    }

    private void requestRefresh(StatisticsRequestCoordinator.Reason reason) {
        if (!panelSelected || !isAdded() || getView() == null) {
            return;
        }
        if (preferOnlineMode) {
            applyMode(true);
            requestStatistics(reason, true);
        } else {
            cancelCurrentLoad();
            requestCoordinator.invalidate();
            loading = false;
            applyMode(false);
            loadOfflineRecords();
        }
    }

    private void showDatePicker() {
        Calendar calendar = Calendar.getInstance();
        try {
            String[] parts = selectedDate.split("-");
            if (parts.length == 3) {
                calendar.set(Calendar.YEAR, Integer.parseInt(parts[0]));
                calendar.set(Calendar.MONTH, Integer.parseInt(parts[1]) - 1);
                calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[2]));
            }
        } catch (Exception ignored) {
        }

        new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    String nextDate = String.format(Locale.getDefault(),
                            "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                    if (nextDate.equals(selectedDate)) {
                        return;
                    }
                    selectedDate = nextDate;
                    tvDate.setText(selectedDate);
                    requestRefresh(StatisticsRequestCoordinator.Reason.FILTER_CHANGED);
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void rebuildPunchOptions() {
        if (spinnerPunchIndex == null || punchOptionAdapter == null) {
            return;
        }

        suppressSelectionCallback = true;
        punchOptions.clear();
        punchOptionAdapter.clear();

        if (!preferOnlineMode) {
            punchOptionAdapter.notifyDataSetChanged();
            selectedPunchOption = null;
            suppressSelectionCallback = false;
            return;
        }

        String currentLabel = selectedPunchOption != null ? selectedPunchOption.label : null;
        punchOptions.addAll(buildPunchOptions(
                SessionManager.get().getCurrentTeamTimeRanges(),
                true
        ));

        List<String> labels = new ArrayList<>(punchOptions.size());
        int selectedIndex = 0;
        for (int i = 0; i < punchOptions.size(); i++) {
            PunchOption option = punchOptions.get(i);
            labels.add(option.label);
            if (option.label.equals(currentLabel)) {
                selectedIndex = i;
            }
        }

        punchOptionAdapter.addAll(labels);
        punchOptionAdapter.notifyDataSetChanged();
        if (!punchOptions.isEmpty()) {
            spinnerPunchIndex.setSelection(selectedIndex, false);
            selectedPunchOption = punchOptions.get(selectedIndex);
        } else {
            selectedPunchOption = null;
        }
        suppressSelectionCallback = false;
    }

    static List<PunchOption> buildPunchOptions(List<String> timeRanges,
                                               boolean onlineMode) {
        List<PunchOption> options = new ArrayList<>();
        if (!onlineMode) {
            return options;
        }

        int clockIndex = 1;
        int regularRangeCount = 0;
        if (timeRanges != null) {
            for (String timeRange : timeRanges) {
                if (timeRange == null) {
                    continue;
                }
                String range = timeRange.trim();
                if (range.isEmpty()) {
                    continue;
                }
                if (onlineMode && regularRangeCount >= 3) {
                    break;
                }
                options.add(new PunchOption(range + " \u4e0a\u73ed", clockIndex++));
                options.add(new PunchOption(range + " \u4e0b\u73ed", clockIndex++));
                regularRangeCount++;
            }
        }
        if (onlineMode) {
            options.add(new PunchOption("\u52a0\u73ed\u4e0a\u73ed", 7));
            options.add(new PunchOption("\u52a0\u73ed\u4e0b\u73ed", 8));
        }
        if (options.isEmpty()) {
            options.add(new PunchOption(UNSCHEDULED_PUNCH_OPTION_LABEL, 0, false));
        }
        return options;
    }

    private void rebuildStatusOptions() {
        statusOptions.clear();
        statusOptions.add(new StatusOption("\u5168\u90e8\u72b6\u6001", 0));
        statusOptions.add(new StatusOption("\u5df2\u6253\u5361", 1));
        statusOptions.add(new StatusOption("\u672a\u6253\u5361", 2));
        statusOptions.add(new StatusOption("\u7279\u6b8a\u6253\u5361", 3));

        List<String> labels = new ArrayList<>(statusOptions.size());
        for (StatusOption option : statusOptions) {
            labels.add(option.label);
        }

        suppressSelectionCallback = true;
        statusAdapter.clear();
        statusAdapter.addAll(labels);
        statusAdapter.notifyDataSetChanged();
        spinnerStatus.setSelection(0, false);
        suppressSelectionCallback = false;
        selectedStatusOption = statusOptions.get(0);
    }

    private void applyMode(boolean useOnline) {
        onlineMode = useOnline;
        layoutRecordFilters.setVisibility(useOnline ? View.VISIBLE : View.GONE);
        spinnerStatus.setVisibility(useOnline ? View.VISIBLE : View.GONE);
        viewSpecialGap.setVisibility(useOnline ? View.VISIBLE : View.GONE);
        layoutSpecialCard.setVisibility(useOnline ? View.VISIBLE : View.GONE);
        recyclerView.setAdapter(useOnline ? onlineAdapter : localAdapter);
    }

    private void requestStatistics(StatisticsRequestCoordinator.Reason reason, boolean reset) {
        final int requestViewToken = viewToken;
        if (!isAdded() || selectedPunchOption == null || selectedPunchOption.clockIndex <= 0) {
            cancelCurrentLoad();
            requestCoordinator.invalidate();
            loading = false;
            postToActiveView(requestViewToken, () -> {
                applyMode(true);
                applyOnlineSummary(null);
                onlineAdapter.replace(new ArrayList<>());
            });
            return;
        }
        if (!reset && loading) {
            return;
        }

        final int requestPage = reset ? 1 : currentPage + 1;
        final String date = selectedDate;
        final String lineCode = SessionManager.get().getLineCode();
        final int clockIndex = selectedPunchOption.clockIndex;
        final Integer clockStatus = selectedStatusOption != null ? selectedStatusOption.value : null;
        final StatisticsRequestCoordinator.QueryKey queryKey =
                new StatisticsRequestCoordinator.QueryKey(
                        date,
                        lineCode,
                        clockIndex,
                        clockStatus,
                        requestPage,
                        PAGE_SIZE);
        final StatisticsRequestCoordinator.RequestToken requestToken =
                requestCoordinator.begin(reason, queryKey);
        if (requestToken == null) {
            return;
        }

        cancelCurrentLoad();
        loading = true;

        currentLoadTask = executor.submit(() -> {
            ApiResult<PunchDto.ClockStatisticsData> result = ApiService.fetchClockStatistics(
                    date,
                    lineCode,
                    clockIndex,
                    clockStatus,
                    requestPage,
                    PAGE_SIZE
            );
            postToActiveView(requestViewToken, () -> {
                if (!requestCoordinator.isCurrent(requestToken)) {
                    return;
                }
                requestCoordinator.complete(requestToken);
                currentLoadTask = null;
                loading = false;
                applyMode(true);
                if (!result.success || result.data == null) {
                    if (reset) {
                        applyOnlineSummary(null);
                        onlineAdapter.replace(new ArrayList<>());
                        currentPage = 0;
                        hasMore = false;
                    }
                    return;
                }

                PunchDto.ClockStatisticsData data = result.data;
                currentPage = requestPage;
                hasMore = (data.page * data.pageSize) < data.total;
                applyOnlineSummary(data);
                if (reset) {
                    onlineAdapter.replace(data.rows);
                } else {
                    onlineAdapter.append(data.rows);
                }
            });
        });
    }

    private void loadOfflineRecords() {
        final int requestViewToken = viewToken;
        final String date = selectedDate;
        final String lineCode = SessionManager.get().getLineCode();

        // Local records are raw punch events. Shift/sign-in/sign-out classification is decided
        // by the server, so the offline panel must not filter by local clock_index.
        List<PunchRecord> records = DatabaseHelper.get(requireContext())
                .getPunchRecordsByDate(date, lineCode);
        int syncedCount = 0;
        int pendingCount = 0;
        for (PunchRecord record : records) {
            if (record.isSynced == 1 || PunchRecord.STATE_SYNCED.equals(record.punchState)) {
                syncedCount++;
            } else {
                pendingCount++;
            }
        }

        final int finalSyncedCount = syncedCount;
        final int finalPendingCount = pendingCount;
        postToActiveView(requestViewToken, () -> {
            applyMode(false);
            tvTotalLabel.setText("\u672c\u5730\u8bb0\u5f55");
            tvClockedLabel.setText("\u5df2\u540c\u6b65");
            tvUnclockedLabel.setText("待处理");
            tvTotalCount.setText(String.valueOf(records.size()));
            tvClockedCount.setText(String.valueOf(finalSyncedCount));
            tvUnclockedCount.setText(String.valueOf(finalPendingCount));
            localAdapter.update(records);
        });
    }

    private void applyOnlineSummary(@Nullable PunchDto.ClockStatisticsData data) {
        tvTotalLabel.setText("\u603b\u4eba\u6570");
        tvClockedLabel.setText("\u5df2\u6253\u5361");
        tvUnclockedLabel.setText("\u672a\u6253\u5361");
        tvSpecialLabel.setText("\u7279\u6b8a\u6253\u5361");
        if (data == null) {
            tvTotalCount.setText("0");
            tvClockedCount.setText("0");
            tvUnclockedCount.setText("0");
            tvSpecialCount.setText("0");
            return;
        }
        int totalCount = data.summary.total > 0 ? data.summary.total : data.total;
        tvTotalCount.setText(String.valueOf(totalCount));
        tvClockedCount.setText(String.valueOf(data.summary.clocked));
        tvUnclockedCount.setText(String.valueOf(data.summary.unclocked));
        tvSpecialCount.setText(String.valueOf(data.summary.special));
    }

    private String buildToday() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    private boolean samePunchOption(@Nullable PunchOption first,
                                    @Nullable PunchOption second) {
        if (first == second) {
            return true;
        }
        return first != null
                && second != null
                && first.clockIndex == second.clockIndex
                && first.freePunch == second.freePunch
                && first.label.equals(second.label);
    }

    private boolean sameStatusOption(@Nullable StatusOption first,
                                     @Nullable StatusOption second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.value == null
                ? second.value == null
                : first.value.equals(second.value);
    }

    private void postToActiveView(int token, Runnable action) {
        uiHandler.post(() -> {
            if (viewGate.isActive(token) && isAdded() && getView() != null) {
                action.run();
            }
        });
    }

    private void cancelCurrentLoad() {
        Future<?> task = currentLoadTask;
        currentLoadTask = null;
        if (task != null) {
            task.cancel(true);
        }
    }

    @Override
    public void onDestroyView() {
        viewGate.close();
        cancelCurrentLoad();
        requestCoordinator.invalidate();
        uiHandler.removeCallbacksAndMessages(null);
        loading = false;
        controlsInitialized = false;
        if (recyclerView != null) {
            recyclerView.setAdapter(null);
        }
        toggleMode = null;
        tvDate = null;
        tvTotalCount = null;
        tvTotalLabel = null;
        tvClockedCount = null;
        tvClockedLabel = null;
        tvUnclockedCount = null;
        tvUnclockedLabel = null;
        tvSpecialCount = null;
        tvSpecialLabel = null;
        viewSpecialGap = null;
        layoutSpecialCard = null;
        layoutRecordFilters = null;
        spinnerPunchIndex = null;
        spinnerStatus = null;
        recyclerView = null;
        localAdapter = null;
        onlineAdapter = null;
        punchOptionAdapter = null;
        statusAdapter = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    static final class PunchOption {
        final String label;
        final int clockIndex;
        final boolean freePunch;

        PunchOption(String label, int clockIndex) {
            this(label, clockIndex, false);
        }

        PunchOption(String label, int clockIndex, boolean freePunch) {
            this.label = label;
            this.clockIndex = clockIndex;
            this.freePunch = freePunch;
        }
    }

    private static final class StatusOption {
        final String label;
        @Nullable
        final Integer value;

        StatusOption(String label, @Nullable Integer value) {
            this.label = label;
            this.value = value;
        }
    }
}
