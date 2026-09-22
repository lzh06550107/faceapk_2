package com.punch.app.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.punch.app.R;
import com.punch.app.face.FaceFileManager;
import com.punch.app.model.PunchRecord;
import com.punch.app.utils.AvatarLoader;
import com.punch.app.utils.Constants;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PunchRecordAdapter extends RecyclerView.Adapter<PunchRecordAdapter.VH> {
    private static final String PUNCH_TYPE_FREE = "free";
    private static final String PUNCH_TYPE_GENERIC = "punch";

    private final Context appContext;
    private List<PunchRecord> items;

    public PunchRecordAdapter(Context context, List<PunchRecord> items) {
        this.appContext = context.getApplicationContext();
        this.items = items;
    }

    public void update(List<PunchRecord> data) {
        this.items = data;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_punch_record, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        PunchRecord record = items.get(position);
        holder.tvName.setText(TextUtils.isEmpty(record.empName) ? "--" : record.empName);
        holder.tvDept.setText(buildDeptText(record));

        String avatarPath = TextUtils.isEmpty(record.empId)
                ? null
                : FaceFileManager.getFaceImagePath(appContext, record.empId);
        AvatarLoader.load(holder.ivAvatar, holder.tvAvatar, record.empName, avatarPath);

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(record.punchTime * 1000L));
        holder.tvTime.setText(time);

        if (PUNCH_TYPE_GENERIC.equals(record.punchType)) {
            holder.tvType.setText("打卡");
            holder.tvType.setBackgroundColor(0xFF0D5FA8);
        } else if (PUNCH_TYPE_FREE.equals(record.punchType)) {
            holder.tvType.setText("自由打卡");
            holder.tvType.setBackgroundColor(0xFF6A1B9A);
        } else {
            boolean signIn = Constants.PUNCH_TYPE_SIGN_IN.equals(record.punchType);
            holder.tvType.setText(signIn ? "上班" : "下班");
            holder.tvType.setBackgroundColor(signIn ? 0xFF0D5FA8 : 0xFF2E7D32);
        }

        holder.tvSync.setText(record.isSynced == 1 ? "已同步" : "待同步");
        holder.tvSync.setTextColor(record.isSynced == 1 ? 0xFF2E7D32 : 0xFFEF6C00);

        if (!TextUtils.isEmpty(record.shiftName)) {
            holder.tvExtra.setVisibility(View.VISIBLE);
            holder.tvExtra.setText(record.shiftName);
        } else {
            holder.tvExtra.setVisibility(View.GONE);
            holder.tvExtra.setText("");
        }
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        AvatarLoader.clear(holder.ivAvatar);
        holder.tvAvatar.setVisibility(View.VISIBLE);
    }

    private String buildDeptText(PunchRecord record) {
        if (!TextUtils.isEmpty(record.dept) && !TextUtils.isEmpty(record.lineCode)) {
            return record.dept + " / " + record.lineCode;
        }
        if (!TextUtils.isEmpty(record.dept)) {
            return record.dept;
        }
        return TextUtils.isEmpty(record.lineCode) ? "" : record.lineCode;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivAvatar;
        final TextView tvAvatar;
        final TextView tvName;
        final TextView tvDept;
        final TextView tvExtra;
        final TextView tvTime;
        final TextView tvType;
        final TextView tvSync;

        VH(View view) {
            super(view);
            ivAvatar = view.findViewById(R.id.iv_avatar);
            tvAvatar = view.findViewById(R.id.tv_avatar);
            tvName = view.findViewById(R.id.tv_name);
            tvDept = view.findViewById(R.id.tv_dept);
            tvExtra = view.findViewById(R.id.tv_extra);
            tvTime = view.findViewById(R.id.tv_time);
            tvType = view.findViewById(R.id.tv_type);
            tvSync = view.findViewById(R.id.tv_sync);
        }
    }
}
