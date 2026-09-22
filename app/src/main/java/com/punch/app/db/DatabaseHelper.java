package com.punch.app.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.punch.app.model.Employee;
import com.punch.app.model.PunchRecord;
import com.punch.app.model.SyncQueueItem;
import com.punch.app.utils.Constants;
import com.punch.app.utils.PunchSnapshotHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;


public class DatabaseHelper extends SQLiteOpenHelper {

    private static final int FACE_FEATURE_QUERY_BATCH_SIZE = 400;
    private static final int FACE_SDK_ID_QUERY_BATCH_SIZE = 400;
    private static DatabaseHelper instance;
    private static String databaseNameOverrideForTest;
    private final Context appContext;
    private final FaceSdkIdRegistry faceSdkIdRegistry;
    private final Object faceSdkIdAllocationLock = new Object();


    public static synchronized DatabaseHelper get(Context ctx) {
        if (instance == null) instance = new DatabaseHelper(ctx.getApplicationContext());
        return instance;
    }

    static synchronized void setDatabaseNameForTest(String databaseName) {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        databaseNameOverrideForTest = databaseName;
    }

    static synchronized void resetForTest() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        databaseNameOverrideForTest = null;
    }

    private static synchronized String resolveDatabaseName() {
        return databaseNameOverrideForTest == null || databaseNameOverrideForTest.trim().isEmpty()
                ? Constants.DB_NAME
                : databaseNameOverrideForTest.trim();
    }

    private DatabaseHelper(Context context) {
        super(context, resolveDatabaseName(), null, Constants.DB_VERSION);
        this.appContext = context.getApplicationContext();
        this.faceSdkIdRegistry = new FaceSdkIdRegistry(new FaceSdkIdRegistry.Store() {
            @Override
            public Integer find(String employeeId) {
                return findFaceSdkId(employeeId);
            }

            @Override
            public int maxId() {
                return findMaxFaceSdkId();
            }

            @Override
            public boolean insert(String employeeId, int sdkId) {
                return insertFaceSdkId(employeeId, sdkId);
            }
        });
    }


    @Override
    public void onCreate(SQLiteDatabase db) {
        // 员工表：保存员工基本信息、人脸图片信息、所属线体以及本地注册状态。
        db.execSQL("CREATE TABLE IF NOT EXISTS employees (" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, dept TEXT, " +
                "face_image_url TEXT, face_image_sha256 TEXT, " +
                "face_version INTEGER DEFAULT 0, face_status TEXT DEFAULT 'enabled', " +
                "local_face_id TEXT, face_registered INTEGER DEFAULT 0, " +
                "assigned_line_code TEXT DEFAULT '', assigned_line_name TEXT DEFAULT '', " +
                "status TEXT DEFAULT 'normal', sync_version INTEGER DEFAULT 0, " +
                "is_deleted INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

        // 打卡记录表：保存本地打卡业务数据，以及是否已同步到服务端的状态。
        createPunchRecordsTable(db);

        // 同步队列表：只记录“待同步动作”和关联记录 ID，真正业务数据仍在各自业务表中。
        // UNIQUE(action, record_id) 防止同一条记录被重复加入相同同步任务。
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "record_id TEXT NOT NULL, action TEXT NOT NULL, " +
                "retry_count INTEGER DEFAULT 0, created_at INTEGER NOT NULL, " +
                "last_retry INTEGER, UNIQUE(action, record_id))");

        // 常用索引：优化按线体、打卡日期、同步状态等场景的查询性能。
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_emp_line ON employees(assigned_line_code)");
        createFaceSdkIdsTable(db);
        createFaceFeaturesTable(db);
        createFaceApplyTasksTable(db);
        createPunchRecordIndexes(db);
    }


    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            migrateEmployeesDropAvatarUrl(db);
            db.execSQL("DROP INDEX IF EXISTS idx_transfer_sync");
            db.execSQL("DROP TABLE IF EXISTS transfer_records");
        }
        if (oldVersion < 4) {
            migratePunchRecordsDropTransferColumns(db);
        }
        if (oldVersion < 5) {
            migratePunchRecordsDropIsEarly(db);
        }
        if (oldVersion < 6) {
            migratePunchRecordsSlimColumns(db);
        }
        if (oldVersion < 7) {
            migratePunchRecordsAddTeamBindingAndClockIndex(db);
        }
        if (oldVersion < 8) {
            migratePunchRecordsAddSnapshotColumns(db);
        }
        if (oldVersion < 9) {
            createFaceSdkIdsTable(db);
        }
        if (oldVersion < 10) {
            createFaceFeaturesTable(db);
        }
        if (oldVersion < 11) {
            createFaceApplyTasksTable(db);
        }
        if (oldVersion < 12) {
            createFaceApplyTasksTable(db);
            addColumnIfMissing(db, "face_apply_tasks", "state", "TEXT NOT NULL DEFAULT 'PENDING'");
            addColumnIfMissing(db, "face_apply_tasks", "next_retry_at", "INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 13) {
            addColumnIfMissing(
                    db,
                    "punch_records",
                    "punch_state",
                    "TEXT NOT NULL DEFAULT 'ACCEPTED'");
            db.execSQL(
                    "UPDATE punch_records SET punch_state=CASE " +
                            "WHEN is_synced=1 THEN ? ELSE ? END",
                    new Object[]{
                            PunchRecord.STATE_SYNCED,
                            PunchRecord.STATE_ACCEPTED
                    });
            createPunchRecordIndexes(db);
        }
    }

    public int getOrCreateFaceSdkId(String employeeId) {
        synchronized (faceSdkIdAllocationLock) {
            return faceSdkIdRegistry.getOrCreate(employeeId);
        }
    }

    public Map<String, Integer> getOrCreateFaceSdkIds(List<String> employeeIds) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (employeeIds == null || employeeIds.isEmpty()) {
            return result;
        }

        List<String> cleanIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String employeeId : employeeIds) {
            if (employeeId == null) {
                continue;
            }
            String cleanId = employeeId.trim();
            if (!cleanId.isEmpty() && seen.add(cleanId)) {
                cleanIds.add(cleanId);
            }
        }
        if (cleanIds.isEmpty()) {
            return result;
        }

        synchronized (faceSdkIdAllocationLock) {
            result.putAll(findFaceSdkIds(cleanIds));
            if (result.size() == cleanIds.size()) {
                return result;
            }

            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                // Re-read inside the write transaction so allocation starts from the latest durable state.
                result.clear();
                result.putAll(findFaceSdkIds(db, cleanIds));
                int nextId = findMaxFaceSdkId(db);
                for (String employeeId : cleanIds) {
                    if (result.containsKey(employeeId)) {
                        continue;
                    }
                    if (nextId == Integer.MAX_VALUE) {
                        throw new IllegalStateException("Face SDK ID space exhausted");
                    }
                    nextId += 1;
                    if (!insertFaceSdkId(db, employeeId, nextId)) {
                        throw new IllegalStateException("Unable to allocate Face SDK ID: " + employeeId);
                    }
                    result.put(employeeId, nextId);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        return result;
    }

    private Map<String, Integer> findFaceSdkIds(List<String> employeeIds) {
        return findFaceSdkIds(getReadableDatabase(), employeeIds);
    }

    private Map<String, Integer> findFaceSdkIds(SQLiteDatabase db, List<String> employeeIds) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int start = 0; start < employeeIds.size(); start += FACE_SDK_ID_QUERY_BATCH_SIZE) {
            int end = Math.min(start + FACE_SDK_ID_QUERY_BATCH_SIZE, employeeIds.size());
            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[end - start];
            for (int i = start; i < end; i++) {
                if (placeholders.length() > 0) {
                    placeholders.append(',');
                }
                placeholders.append('?');
                args[i - start] = employeeIds.get(i);
            }
            Cursor c = db.rawQuery(
                    "SELECT emp_id, sdk_id FROM face_sdk_ids WHERE emp_id IN (" + placeholders + ")",
                    args);
            try {
                while (c.moveToNext()) {
                    result.put(c.getString(0), c.getInt(1));
                }
            } finally {
                c.close();
            }
        }
        return result;
    }

    private Integer findFaceSdkId(String employeeId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT sdk_id FROM face_sdk_ids WHERE emp_id=?",
                new String[]{employeeId});
        try {
            return c.moveToFirst() ? c.getInt(0) : null;
        } finally {
            c.close();
        }
    }

    private int findMaxFaceSdkId() {
        return findMaxFaceSdkId(getReadableDatabase());
    }

    private int findMaxFaceSdkId(SQLiteDatabase db) {
        Cursor c = db.rawQuery(
                "SELECT COALESCE(MAX(sdk_id), 0) FROM face_sdk_ids", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    private boolean insertFaceSdkId(String employeeId, int sdkId) {
        return insertFaceSdkId(getWritableDatabase(), employeeId, sdkId);
    }

    private boolean insertFaceSdkId(SQLiteDatabase db, String employeeId, int sdkId) {
        ContentValues values = new ContentValues();
        values.put("emp_id", employeeId);
        values.put("sdk_id", sdkId);
        return db.insertWithOnConflict(
                "face_sdk_ids", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    private void createFaceSdkIdsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS face_sdk_ids (" +
                "emp_id TEXT PRIMARY KEY, sdk_id INTEGER NOT NULL UNIQUE CHECK(sdk_id > 0))");
    }

    public byte[] getValidFaceFeature(String empId,
                                      int faceVersion,
                                      String imageSha256,
                                      int featureSchemaVersion) {
        if (empId == null || empId.trim().isEmpty()) {
            return null;
        }
        String safeSha256 = imageSha256 == null ? "" : imageSha256.trim();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT feature FROM face_features " +
                        "WHERE emp_id=? AND face_version=? " +
                        "AND COALESCE(image_sha256, '')=? AND feature_schema_version=? LIMIT 1",
                new String[]{
                        empId.trim(),
                        String.valueOf(faceVersion),
                        safeSha256,
                        String.valueOf(featureSchemaVersion)
                });
        try {
            if (!c.moveToFirst()) {
                return null;
            }
            byte[] feature = c.getBlob(0);
            return feature != null && feature.length == 512 ? feature : null;
        } finally {
            c.close();
        }
    }

    public Map<String, FaceFeatureCacheEntry> getFaceFeaturesByEmployeeIds(List<String> empIds) {
        Map<String, FaceFeatureCacheEntry> result = new LinkedHashMap<>();
        if (empIds == null || empIds.isEmpty()) {
            return result;
        }

        List<String> cleanIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String empId : empIds) {
            if (empId == null) {
                continue;
            }
            String cleanId = empId.trim();
            if (!cleanId.isEmpty() && seen.add(cleanId)) {
                cleanIds.add(cleanId);
            }
        }

        SQLiteDatabase db = getReadableDatabase();
        for (int start = 0; start < cleanIds.size(); start += FACE_FEATURE_QUERY_BATCH_SIZE) {
            int end = Math.min(start + FACE_FEATURE_QUERY_BATCH_SIZE, cleanIds.size());
            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[end - start];
            for (int i = start; i < end; i++) {
                if (placeholders.length() > 0) {
                    placeholders.append(',');
                }
                placeholders.append('?');
                args[i - start] = cleanIds.get(i);
            }

            Cursor c = db.rawQuery(
                    "SELECT emp_id, face_version, COALESCE(image_sha256, ''), " +
                            "feature_schema_version, feature FROM face_features " +
                            "WHERE emp_id IN (" + placeholders + ")",
                    args);
            try {
                while (c.moveToNext()) {
                    String empId = c.getString(0);
                    result.put(empId, new FaceFeatureCacheEntry(
                            c.getInt(1),
                            c.getString(2),
                            c.getInt(3),
                            c.getBlob(4)));
                }
            } finally {
                c.close();
            }
        }
        return result;
    }

    public boolean upsertFaceFeature(String empId,
                                     int faceVersion,
                                     String imageSha256,
                                     int featureSchemaVersion,
                                     byte[] feature) {
        if (empId == null || empId.trim().isEmpty() || feature == null || feature.length != 512) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("emp_id", empId.trim());
        values.put("face_version", faceVersion);
        values.put("image_sha256", imageSha256 == null ? "" : imageSha256.trim());
        values.put("feature_schema_version", featureSchemaVersion);
        values.put("feature", feature);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "face_features", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    private void createFaceFeaturesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS face_features (" +
                "emp_id TEXT PRIMARY KEY, " +
                "face_version INTEGER NOT NULL DEFAULT 0, " +
                "image_sha256 TEXT NOT NULL DEFAULT '', " +
                "feature_schema_version INTEGER NOT NULL, " +
                "feature BLOB NOT NULL, " +
                "updated_at INTEGER NOT NULL DEFAULT 0)");
    }

    private void createFaceApplyTasksTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS face_apply_tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "emp_id TEXT NOT NULL UNIQUE, " +
                "operation TEXT NOT NULL, " +
                "face_version INTEGER NOT NULL DEFAULT 0, " +
                "state TEXT NOT NULL DEFAULT 'PENDING', " +
                "retry_count INTEGER NOT NULL DEFAULT 0, " +
                "next_retry_at INTEGER NOT NULL DEFAULT 0, " +
                "last_error TEXT NOT NULL DEFAULT '', " +
                "updated_at INTEGER NOT NULL DEFAULT 0)");
    }

    public boolean commitEmployeeFaceBatch(List<FaceBatchWrite> writes) {
        if (writes == null || writes.isEmpty()) {
            return true;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (FaceBatchWrite write : writes) {
                if (write == null) {
                    continue;
                }
                if (write.markDeleted) {
                    markEmployeeDeletedForBatch(db, write.employeeId, write.updatedAt);
                } else if (write.employee != null) {
                    writeEmployeeForBatch(db, write.employee);
                }
                if (write.feature != null) {
                    writeFaceFeatureForBatch(db, write);
                }
                if (write.applyOperation != null && !write.applyOperation.isEmpty()) {
                    writeFaceApplyTaskForBatch(db, write);
                }
            }
            db.setTransactionSuccessful();
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    private void writeEmployeeForBatch(SQLiteDatabase db, Employee employee) {
        ContentValues v = employeeValues(employee);
        if (db.insertWithOnConflict("employees", null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("employee batch write failed: " + employee.id);
        }
    }

    private void markEmployeeDeletedForBatch(SQLiteDatabase db, String employeeId, long updatedAt) {
        if (employeeId == null || employeeId.trim().isEmpty()) {
            throw new IllegalArgumentException("employeeId is empty");
        }
        ContentValues values = new ContentValues();
        values.put("is_deleted", 1);
        values.put("face_registered", 0);
        values.putNull("local_face_id");
        if (updatedAt > 0) {
            values.put("updated_at", updatedAt);
        }
        db.update("employees", values, "id=?", new String[]{employeeId});
    }

    private void writeFaceFeatureForBatch(SQLiteDatabase db, FaceBatchWrite write) {
        if (write.feature == null || write.feature.length != 512 || write.employee == null) {
            throw new IllegalArgumentException("invalid face feature batch write");
        }
        ContentValues values = new ContentValues();
        values.put("emp_id", write.employee.id);
        values.put("face_version", write.employee.faceVersion);
        values.put("image_sha256", write.employee.faceImageSha256 == null ? "" : write.employee.faceImageSha256.trim());
        values.put("feature_schema_version", write.featureSchemaVersion);
        values.put("feature", write.feature);
        values.put("updated_at", System.currentTimeMillis());
        if (db.insertWithOnConflict("face_features", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("face feature batch write failed: " + write.employee.id);
        }
    }

    private void writeFaceApplyTaskForBatch(SQLiteDatabase db, FaceBatchWrite write) {
        String employeeId = write.employee != null ? write.employee.id : write.employeeId;
        if (employeeId == null || employeeId.trim().isEmpty()) {
            throw new IllegalArgumentException("face apply employeeId is empty");
        }
        ContentValues values = new ContentValues();
        values.put("emp_id", employeeId.trim());
        values.put("operation", write.applyOperation);
        values.put("face_version", write.faceVersion);
        values.put("state", "PENDING");
        values.put("retry_count", 0);
        values.put("next_retry_at", 0);
        values.put("last_error", "");
        values.put("updated_at", System.currentTimeMillis());
        if (db.insertWithOnConflict("face_apply_tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("face apply task write failed: " + employeeId);
        }
    }

    public boolean hasFaceApplyTask(String empId, String operation) {
        if (empId == null || empId.trim().isEmpty() || operation == null || operation.trim().isEmpty()) {
            return false;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM face_apply_tasks WHERE emp_id=? AND operation=? LIMIT 1",
                new String[]{empId.trim(), operation.trim()});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public boolean hasPendingFaceApplyTask(String empId, String operation) {
        if (empId == null || empId.trim().isEmpty() || operation == null || operation.trim().isEmpty()) {
            return false;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM face_apply_tasks WHERE emp_id=? AND operation=? AND state='PENDING' LIMIT 1",
                new String[]{empId.trim(), operation.trim()});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public Set<String> getPendingFaceApplyEmployeeIds(String operation) {
        Set<String> result = new HashSet<>();
        if (operation == null || operation.trim().isEmpty()) {
            return result;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT emp_id FROM face_apply_tasks WHERE operation=? AND state='PENDING'",
                new String[]{operation.trim()});
        try {
            while (c.moveToNext()) {
                String empId = c.getString(0);
                if (empId != null && !empId.trim().isEmpty()) {
                    result.add(empId.trim());
                }
            }
        } finally {
            c.close();
        }
        return result;
    }

    public int getPendingFaceApplyTaskCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM face_apply_tasks WHERE state='PENDING'",
                null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public FaceApplyTask getNextFaceApplyTask() {
        return getNextFaceApplyTask(0L);
    }

    public FaceApplyTask getNextFaceApplyTask(long afterId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, emp_id, operation, face_version, state, retry_count, next_retry_at, last_error, updated_at " +
                        "FROM face_apply_tasks WHERE id>? ORDER BY id ASC LIMIT 1",
                new String[]{String.valueOf(afterId)});
        try {
            return readFaceApplyTask(c);
        } finally {
            c.close();
        }
    }

    public FaceApplyTask getNextReadyFaceApplyTask(long afterId, long nowMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, emp_id, operation, face_version, state, retry_count, next_retry_at, last_error, updated_at " +
                        "FROM face_apply_tasks WHERE id>? AND state='PENDING' AND next_retry_at<=? " +
                        "ORDER BY id ASC LIMIT 1",
                new String[]{String.valueOf(afterId), String.valueOf(nowMs)});
        try {
            return readFaceApplyTask(c);
        } finally {
            c.close();
        }
    }

    public long getNextFaceApplyRetryAt() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MIN(next_retry_at) FROM face_apply_tasks " +
                        "WHERE state='PENDING' AND next_retry_at>0",
                null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    private FaceApplyTask readFaceApplyTask(Cursor c) {
        if (!c.moveToFirst()) {
            return null;
        }
        FaceApplyTask task = new FaceApplyTask();
        task.id = c.getLong(0);
        task.empId = c.getString(1);
        task.operation = c.getString(2);
        task.faceVersion = c.getInt(3);
        task.state = c.getString(4);
        task.retryCount = c.getInt(5);
        task.nextRetryAt = c.getLong(6);
        task.lastError = c.getString(7);
        task.updatedAt = c.getLong(8);
        return task;
    }

    public boolean deleteFaceApplyTask(long taskId) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete("face_apply_tasks", "id=?", new String[]{String.valueOf(taskId)}) > 0;
    }

    public void markFaceApplyTaskRetry(long taskId, String error, long nextRetryAt) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "UPDATE face_apply_tasks SET state='PENDING', retry_count=retry_count+1, " +
                        "next_retry_at=?, last_error=?, updated_at=? WHERE id=?",
                new Object[]{Math.max(0L, nextRetryAt), error == null ? "" : error,
                        System.currentTimeMillis(), taskId});
    }

    public void markFaceApplyTaskPermanentFailed(long taskId, String error) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "UPDATE face_apply_tasks SET state='FAILED', next_retry_at=0, last_error=?, updated_at=? WHERE id=?",
                new Object[]{error == null ? "" : error, System.currentTimeMillis(), taskId});
    }

    public boolean completeFaceApplyTask(long taskId, String empId, String localFaceId, boolean registered) {
        if (empId == null || empId.trim().isEmpty()) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery(
                    "SELECT id FROM face_apply_tasks WHERE id=? AND emp_id=? LIMIT 1",
                    new String[]{String.valueOf(taskId), empId});
            boolean current;
            try {
                current = c.moveToFirst();
            } finally {
                c.close();
            }
            if (!current) {
                db.setTransactionSuccessful();
                return false;
            }
            ContentValues values = new ContentValues();
            if (localFaceId == null) {
                values.putNull("local_face_id");
            } else {
                values.put("local_face_id", localFaceId);
            }
            values.put("face_registered", registered ? 1 : 0);
            db.update("employees", values, "id=?", new String[]{empId});
            db.delete("face_apply_tasks", "id=?", new String[]{String.valueOf(taskId)});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public static final class FaceFeatureCacheEntry {
        public final int faceVersion;
        public final String imageSha256;
        public final int featureSchemaVersion;
        public final byte[] feature;

        FaceFeatureCacheEntry(int faceVersion,
                              String imageSha256,
                              int featureSchemaVersion,
                              byte[] feature) {
            this.faceVersion = faceVersion;
            this.imageSha256 = imageSha256 == null ? "" : imageSha256;
            this.featureSchemaVersion = featureSchemaVersion;
            this.feature = feature;
        }
    }

    public static final class FaceApplyTask {
        public long id;
        public String empId;
        public String operation;
        public int faceVersion;
        public String state;
        public int retryCount;
        public long nextRetryAt;
        public String lastError;
        public long updatedAt;
    }

    public static final class FaceBatchWrite {
        public static final String OP_UPSERT = "UPSERT";
        public static final String OP_REMOVE = "REMOVE";

        public Employee employee;
        public String employeeId;
        public boolean markDeleted;
        public long updatedAt;
        public byte[] feature;
        public int featureSchemaVersion;
        public String applyOperation;
        public int faceVersion;

        public static FaceBatchWrite upsert(Employee employee) {
            FaceBatchWrite write = new FaceBatchWrite();
            write.employee = employee;
            write.employeeId = employee == null ? null : employee.id;
            write.faceVersion = employee == null ? 0 : employee.faceVersion;
            return write;
        }

        public static FaceBatchWrite remove(String employeeId, long updatedAt) {
            FaceBatchWrite write = new FaceBatchWrite();
            write.employeeId = employeeId;
            write.markDeleted = true;
            write.updatedAt = updatedAt;
            write.applyOperation = OP_REMOVE;
            return write;
        }

        public FaceBatchWrite withFeature(byte[] value, int schemaVersion) {
            this.feature = value;
            this.featureSchemaVersion = schemaVersion;
            this.applyOperation = OP_UPSERT;
            this.faceVersion = employee == null ? faceVersion : employee.faceVersion;
            return this;
        }

        public FaceBatchWrite withRemoveTask() {
            this.applyOperation = OP_REMOVE;
            this.faceVersion = employee == null ? faceVersion : employee.faceVersion;
            return this;
        }
    }


    private ContentValues employeeValues(Employee e) {
        ContentValues v = new ContentValues();
        v.put("id", e.id); v.put("name", e.name); v.put("dept", e.dept);
        v.put("face_image_url", e.faceImageUrl);
        v.put("face_image_sha256", e.faceImageSha256); v.put("face_version", e.faceVersion);
        v.put("face_status", e.faceStatus); v.put("local_face_id", e.localFaceId);
        v.put("face_registered", e.faceRegistered);
        v.put("assigned_line_code", e.assignedLineCode);
        v.put("assigned_line_name", e.assignedLineName);
        v.put("status", e.status); v.put("sync_version", e.syncVersion);
        v.put("is_deleted", e.isDeleted); v.put("updated_at", e.updatedAt);
        return v;
    }

    public void upsertEmployee(Employee e) {
        ContentValues v = employeeValues(e);
        getWritableDatabase().insertWithOnConflict("employees", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }


    public void upsertEmployees(List<Employee> list) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Employee e : list) {
                ContentValues v = new ContentValues();
                v.put("id", e.id); v.put("name", e.name); v.put("dept", e.dept);
                v.put("face_image_url", e.faceImageUrl);
                v.put("face_image_sha256", e.faceImageSha256); v.put("face_version", e.faceVersion);
                v.put("face_status", e.faceStatus); v.put("local_face_id", e.localFaceId);
                v.put("face_registered", e.faceRegistered);
                v.put("assigned_line_code", e.assignedLineCode);
                v.put("assigned_line_name", e.assignedLineName);
                v.put("status", e.status); v.put("sync_version", e.syncVersion);
                v.put("is_deleted", e.isDeleted); v.put("updated_at", e.updatedAt);
                db.insertWithOnConflict("employees", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }


    public Employee getEmployee(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM employees WHERE id=?", new String[]{id});
        try { return c.moveToFirst() ? mapEmployee(c) : null; }
        finally { c.close(); }
    }


    public List<Employee> getAllActiveEmployees() {
        return queryEmployees("is_deleted=0 AND face_status='enabled'", null);
    }

    public List<Employee> getAllEmployeesForDebug() {
        List<Employee> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM employees ORDER BY updated_at DESC, id ASC",
                null);
        try {
            while (c.moveToNext()) {
                list.add(mapEmployee(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    public int getActiveEmployeeCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM employees WHERE is_deleted=0 AND face_status='enabled'",
                null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public Set<String> getActiveRegisteredFaceEmployeeIds() {
        Set<String> employeeIds = new HashSet<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM employees " +
                        "WHERE is_deleted=0 AND face_status='enabled' " +
                        "AND face_registered=1 AND local_face_id IS NOT NULL AND local_face_id<>'' " +
                        "AND face_image_url IS NOT NULL AND face_image_url<>''",
                null);
        try {
            while (c.moveToNext()) {
                String empId = c.getString(0);
                if (empId != null && !empId.trim().isEmpty()) {
                    employeeIds.add(empId.trim());
                }
            }
        } finally {
            c.close();
        }
        return employeeIds;
    }

    public Set<String> getPendingFaceApplyTaskEmployeeIds(String operation) {
        Set<String> employeeIds = new HashSet<>();
        if (operation == null || operation.trim().isEmpty()) {
            return employeeIds;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT emp_id FROM face_apply_tasks WHERE state='PENDING' AND operation=?",
                new String[]{operation.trim()});
        try {
            while (c.moveToNext()) {
                String empId = c.getString(0);
                if (empId != null && !empId.trim().isEmpty()) {
                    employeeIds.add(empId.trim());
                }
            }
        } finally {
            c.close();
        }
        return employeeIds;
    }

    public Set<String> getFaceRuntimeEmployeeIdsMissingValidFeature(int featureSchemaVersion) {
        Set<String> employeeIds = new HashSet<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT e.id FROM employees e " +
                        "WHERE e.is_deleted=0 AND e.face_status='enabled' " +
                        "AND e.face_image_url IS NOT NULL AND e.face_image_url<>'' " +
                        "AND ((e.face_registered=1 AND e.local_face_id IS NOT NULL AND e.local_face_id<>'') " +
                        "OR EXISTS (SELECT 1 FROM face_apply_tasks t WHERE t.emp_id=e.id " +
                        "AND t.state='PENDING' AND t.operation='UPSERT')) " +
                        "AND NOT EXISTS (SELECT 1 FROM face_features f WHERE f.emp_id=e.id " +
                        "AND f.face_version=e.face_version " +
                        "AND COALESCE(f.image_sha256, '')=COALESCE(e.face_image_sha256, '') " +
                        "AND f.feature_schema_version=? AND LENGTH(f.feature)=512)",
                new String[]{String.valueOf(featureSchemaVersion)});
        try {
            while (c.moveToNext()) {
                String empId = c.getString(0);
                if (empId != null && !empId.trim().isEmpty()) {
                    employeeIds.add(empId.trim());
                }
            }
        } finally {
            c.close();
        }
        return employeeIds;
    }


    public Map<String, String> getAvailableLines() {
        Map<String, String> lines = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT assigned_line_code, assigned_line_name FROM employees " +
                        "WHERE is_deleted=0 AND assigned_line_code IS NOT NULL AND assigned_line_code<>'' " +
                        "ORDER BY assigned_line_code ASC",
                null);
        try {
            while (c.moveToNext()) {
                String lineCode = c.getString(c.getColumnIndexOrThrow("assigned_line_code"));
                String lineName = c.getString(c.getColumnIndexOrThrow("assigned_line_name"));
                lines.put(lineCode, lineName == null ? "" : lineName);
            }
        } finally {
            c.close();
        }
        return lines;
    }


    public List<Employee> getEmployeesByLine(String lineCode) {
        return queryEmployees("assigned_line_code=? AND is_deleted=0", new String[]{lineCode});
    }


    public List<Employee> getUnregisteredFaces() {
        return queryEmployees(
                "face_registered=0 AND is_deleted=0 AND face_status='enabled' AND face_image_url IS NOT NULL",
                null);
    }


    public void updateFaceRegistration(String empId, String localFaceId, boolean registered) {
        ContentValues v = new ContentValues();
        v.put("local_face_id", localFaceId);
        v.put("face_registered", registered ? 1 : 0);
        getWritableDatabase().update("employees", v, "id=?", new String[]{empId});
    }


    public void updateEmployeeLineAssignment(String empId, String lineCode, String lineName) {
        ContentValues v = new ContentValues();
        v.put("assigned_line_code", lineCode);
        v.put("assigned_line_name", lineName);
        getWritableDatabase().update("employees", v, "id=?", new String[]{empId});
    }


    public void markEmployeesDeleted(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues(); v.put("is_deleted", 1);
            for (String id : ids) db.update("employees", v, "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void markEmployeeDeleted(String id, long updatedAt) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        ContentValues v = new ContentValues();
        v.put("is_deleted", 1);
        if (updatedAt > 0) {
            v.put("updated_at", updatedAt);
        }
        getWritableDatabase().update("employees", v, "id=?", new String[]{id});
    }

    public void removeEmployee(String id) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("face_apply_tasks", "emp_id=?", new String[]{id});
            db.delete("face_features", "emp_id=?", new String[]{id});
            db.delete("employees", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void clearAllEmployees() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("face_apply_tasks", null, null);
            db.delete("face_features", null, null);
            db.delete("employees", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }


    private List<Employee> queryEmployees(String where, String[] args) {
        List<Employee> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM employees" + (where != null ? " WHERE " + where : ""), args);
        try { while (c.moveToNext()) list.add(mapEmployee(c)); }
        finally { c.close(); }
        return list;
    }


    private Employee mapEmployee(Cursor c) {
        Employee e = new Employee();
        e.id = c.getString(c.getColumnIndexOrThrow("id"));
        e.name = c.getString(c.getColumnIndexOrThrow("name"));
        e.dept = c.getString(c.getColumnIndexOrThrow("dept"));
        e.faceImageUrl = c.getString(c.getColumnIndexOrThrow("face_image_url"));
        e.faceImageSha256 = c.getString(c.getColumnIndexOrThrow("face_image_sha256"));
        e.faceVersion = c.getInt(c.getColumnIndexOrThrow("face_version"));
        e.faceStatus = c.getString(c.getColumnIndexOrThrow("face_status"));
        e.localFaceId = c.getString(c.getColumnIndexOrThrow("local_face_id"));
        e.faceRegistered = c.getInt(c.getColumnIndexOrThrow("face_registered"));
        e.assignedLineCode = c.getString(c.getColumnIndexOrThrow("assigned_line_code"));
        e.assignedLineName = c.getString(c.getColumnIndexOrThrow("assigned_line_name"));
        e.status = c.getString(c.getColumnIndexOrThrow("status"));
        e.syncVersion = c.getInt(c.getColumnIndexOrThrow("sync_version"));
        e.isDeleted = c.getInt(c.getColumnIndexOrThrow("is_deleted"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return e;
    }


    public boolean insertPunchRecord(PunchRecord r) {
        return r != null && insertPunchRecord(getWritableDatabase(), r) != -1L;
    }

    /**
     * Atomic local boundary used before any server acceptance request.
     * Either both the punch intent and its durable work item exist, or neither exists.
     */
    public boolean insertPunchRecordAndEnqueue(PunchRecord r, String action) {
        if (r == null || action == null || action.trim().isEmpty()) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (insertPunchRecord(db, r) == -1L) {
                return false;
            }
            if (!enqueueSyncItem(db, r.clientRecordId, action)) {
                return false;
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private long insertPunchRecord(SQLiteDatabase db, PunchRecord r) {
        ContentValues v = punchValues(r);
        return db.insertWithOnConflict(
                "punch_records", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private ContentValues punchValues(PunchRecord r) {
        ContentValues v = new ContentValues();
        v.put("id", r.id);
        v.put("client_record_id", r.clientRecordId);
        v.put("emp_id", r.empId);
        v.put("emp_name", r.empName);
        v.put("dept", r.dept);
        v.put("punch_time", r.punchTime);
        v.put("punch_date", r.punchDate);
        v.put("punch_type", r.punchType);
        v.put("shift_name", r.shiftName);
        v.put("line_code", r.lineCode);
        v.put("team_binding_id", r.teamBindingId);
        v.put("clock_index", r.clockIndex);
        v.put("match_score", r.matchScore);
        v.put("snap_image_path", r.snapImagePath);
        v.put("snap_image_mime_type", r.snapImageMimeType);
        v.put("snap_image_width", r.snapImageWidth);
        v.put("snap_image_height", r.snapImageHeight);
        v.put("snap_image_size", r.snapImageSize);
        v.put("snap_captured_at", r.snapCapturedAt);
        v.put("is_synced", r.isSynced);
        String state = r.punchState == null || r.punchState.trim().isEmpty()
                ? (r.isSynced == 1 ? PunchRecord.STATE_SYNCED : PunchRecord.STATE_ACCEPTED)
                : r.punchState.trim();
        v.put("punch_state", state);
        return v;
    }


    public List<PunchRecord> getPunchRecordsByDate(String date, String lineCode) {
        List<PunchRecord> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM punch_records " +
                        "WHERE punch_date=? AND line_code=? AND punch_state<>? " +
                        "ORDER BY punch_time DESC",
                new String[]{date, lineCode, PunchRecord.STATE_ACCEPTING});
        try { while (c.moveToNext()) list.add(mapPunch(c)); }
        finally { c.close(); }
        return list;
    }


    public List<PunchRecord> getUnsyncedPunchRecords() {
        List<PunchRecord> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM punch_records WHERE is_synced=0 " +
                        "AND punch_state IN (?, ?) ORDER BY punch_time ASC",
                new String[]{PunchRecord.STATE_ACCEPTED, PunchRecord.STATE_UPLOADING});
        try { while (c.moveToNext()) list.add(mapPunch(c)); }
        finally { c.close(); }
        return list;
    }

    public PunchRecord getUnsyncedPunchRecord(String clientRecordId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM punch_records WHERE client_record_id=? AND is_synced=0 " +
                        "AND punch_state IN (?, ?)",
                new String[]{
                        clientRecordId,
                        PunchRecord.STATE_ACCEPTED,
                        PunchRecord.STATE_UPLOADING
                });
        try {
            return c.moveToFirst() ? mapPunch(c) : null;
        } finally {
            c.close();
        }
    }


    public PunchRecord getPunchRecord(String clientRecordId) {
        if (clientRecordId == null || clientRecordId.trim().isEmpty()) {
            return null;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM punch_records WHERE client_record_id=? LIMIT 1",
                new String[]{clientRecordId.trim()});
        try {
            return c.moveToFirst() ? mapPunch(c) : null;
        } finally {
            c.close();
        }
    }

    public boolean hasPendingPunchAcceptanceForEmployee(String empId) {
        if (empId == null || empId.trim().isEmpty()) {
            return false;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM punch_records " +
                        "WHERE emp_id=? AND punch_state=? AND is_synced=0 LIMIT 1",
                new String[]{empId.trim(), PunchRecord.STATE_ACCEPTING});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public boolean markPunchAcceptedAndQueueUpload(String clientRecordId) {
        if (clientRecordId == null || clientRecordId.trim().isEmpty()) {
            return false;
        }
        String recordId = clientRecordId.trim();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery(
                    "SELECT punch_state, is_synced FROM punch_records " +
                            "WHERE client_record_id=? LIMIT 1",
                    new String[]{recordId});
            String state;
            int synced;
            try {
                if (!c.moveToFirst()) {
                    return false;
                }
                state = c.getString(0);
                synced = c.getInt(1);
            } finally {
                c.close();
            }

            db.delete(
                    "sync_queue",
                    "action=? AND record_id=?",
                    new String[]{Constants.ACTION_PUNCH_ACCEPT, recordId});

            if (synced == 1 || PunchRecord.STATE_SYNCED.equals(state)) {
                db.setTransactionSuccessful();
                return true;
            }

            ContentValues values = new ContentValues();
            values.put("punch_state", PunchRecord.STATE_ACCEPTED);
            values.put("is_synced", 0);
            db.update(
                    "punch_records",
                    values,
                    "client_record_id=?",
                    new String[]{recordId});

            if (!enqueueSyncItem(db, recordId, Constants.ACTION_PUNCH_PUSH)) {
                return false;
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public boolean markPunchUploading(String clientRecordId) {
        if (clientRecordId == null || clientRecordId.trim().isEmpty()) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("punch_state", PunchRecord.STATE_UPLOADING);
        return getWritableDatabase().update(
                "punch_records",
                values,
                "client_record_id=? AND is_synced=0 AND punch_state=?",
                new String[]{
                        clientRecordId.trim(),
                        PunchRecord.STATE_ACCEPTED
                }) > 0;
    }

    public void markPunchUploadPending(String clientRecordId) {
        if (clientRecordId == null || clientRecordId.trim().isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("punch_state", PunchRecord.STATE_ACCEPTED);
        getWritableDatabase().update(
                "punch_records",
                values,
                "client_record_id=? AND is_synced=0 AND punch_state=?",
                new String[]{
                        clientRecordId.trim(),
                        PunchRecord.STATE_UPLOADING
                });
    }

    public boolean discardPunchIntent(String clientRecordId) {
        if (clientRecordId == null || clientRecordId.trim().isEmpty()) {
            return false;
        }
        String recordId = clientRecordId.trim();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("sync_queue", "record_id=?", new String[]{recordId});
            int deleted = db.delete(
                    "punch_records",
                    "client_record_id=? AND punch_state=?",
                    new String[]{recordId, PunchRecord.STATE_ACCEPTING});
            db.setTransactionSuccessful();
            return deleted > 0;
        } finally {
            db.endTransaction();
        }
    }


    public void markPunchSynced(String id) {
        ContentValues v = new ContentValues();
        v.put("is_synced", 1);
        v.put("punch_state", PunchRecord.STATE_SYNCED);
        getWritableDatabase().update("punch_records", v, "id=?", new String[]{id});
    }


    private PunchRecord mapPunch(Cursor c) {
        PunchRecord r = new PunchRecord();
        r.id = c.getString(c.getColumnIndexOrThrow("id"));
        r.clientRecordId = c.getString(c.getColumnIndexOrThrow("client_record_id"));
        r.empId = c.getString(c.getColumnIndexOrThrow("emp_id"));
        r.empName = c.getString(c.getColumnIndexOrThrow("emp_name"));
        r.dept = c.getString(c.getColumnIndexOrThrow("dept"));
        r.punchTime = c.getLong(c.getColumnIndexOrThrow("punch_time"));
        r.punchDate = c.getString(c.getColumnIndexOrThrow("punch_date"));
        r.punchType = c.getString(c.getColumnIndexOrThrow("punch_type"));
        r.shiftName = c.getString(c.getColumnIndexOrThrow("shift_name"));
        r.lineCode = c.getString(c.getColumnIndexOrThrow("line_code"));
        r.teamBindingId = c.getInt(c.getColumnIndexOrThrow("team_binding_id"));
        r.clockIndex = c.getInt(c.getColumnIndexOrThrow("clock_index"));
        r.matchScore = c.getDouble(c.getColumnIndexOrThrow("match_score"));
        r.snapImagePath = c.getString(c.getColumnIndexOrThrow("snap_image_path"));
        r.snapImageMimeType = c.getString(c.getColumnIndexOrThrow("snap_image_mime_type"));
        r.snapImageWidth = c.getInt(c.getColumnIndexOrThrow("snap_image_width"));
        r.snapImageHeight = c.getInt(c.getColumnIndexOrThrow("snap_image_height"));
        r.snapImageSize = c.getLong(c.getColumnIndexOrThrow("snap_image_size"));
        r.snapCapturedAt = c.getLong(c.getColumnIndexOrThrow("snap_captured_at"));
        r.isSynced = c.getInt(c.getColumnIndexOrThrow("is_synced"));
        r.punchState = c.getString(c.getColumnIndexOrThrow("punch_state"));
        return r;
    }


    public void enqueueSyncItem(String recordId, String action) {
        enqueueSyncItem(getWritableDatabase(), recordId, action);
    }

    private boolean enqueueSyncItem(SQLiteDatabase db, String recordId, String action) {
        if (db == null || recordId == null || recordId.trim().isEmpty()
                || action == null || action.trim().isEmpty()) {
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("record_id", recordId.trim());
        v.put("action", action.trim());
        v.put("retry_count", 0);
        v.put("created_at", System.currentTimeMillis() / 1000);
        long inserted = db.insertWithOnConflict(
                "sync_queue", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted != -1L) {
            return true;
        }
        Cursor c = db.rawQuery(
                "SELECT 1 FROM sync_queue WHERE action=? AND record_id=? LIMIT 1",
                new String[]{action.trim(), recordId.trim()});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }


    public List<SyncQueueItem> getSyncQueue(String action) {
        return querySyncQueue(action, null, 0);
    }

    public List<SyncQueueItem> getSyncQueue(String action, int limit) {
        return querySyncQueue(action, null, limit);
    }

    public List<SyncQueueItem> getRetryableSyncQueue(String action, int maxRetries, int limit) {
        return querySyncQueue(action, maxRetries, limit);
    }

    private List<SyncQueueItem> querySyncQueue(String action,
                                               Integer maxRetries,
                                               int limit) {
        List<SyncQueueItem> list = new ArrayList<>();
        List<String> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM sync_queue");
        if (action != null || maxRetries != null) {
            sql.append(" WHERE ");
            if (action != null) {
                sql.append("action=?");
                args.add(action);
            }
            if (maxRetries != null) {
                if (action != null) {
                    sql.append(" AND ");
                }
                sql.append("retry_count<?");
                args.add(String.valueOf(maxRetries));
            }
        }
        sql.append(" ORDER BY created_at ASC, id ASC");
        if (limit > 0) {
            sql.append(" LIMIT ?");
            args.add(String.valueOf(limit));
        }
        Cursor c = getReadableDatabase().rawQuery(
                sql.toString(), args.isEmpty() ? null : args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                SyncQueueItem item = new SyncQueueItem();
                item.id = c.getInt(c.getColumnIndexOrThrow("id"));
                item.recordId = c.getString(c.getColumnIndexOrThrow("record_id"));
                item.action = c.getString(c.getColumnIndexOrThrow("action"));
                item.retryCount = c.getInt(c.getColumnIndexOrThrow("retry_count"));
                item.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                item.lastRetry = c.getLong(c.getColumnIndexOrThrow("last_retry"));
                list.add(item);
            }
        } finally { c.close(); }
        return list;
    }


    public void removeSyncQueueItem(int id) {
        getWritableDatabase().delete("sync_queue", "id=?", new String[]{String.valueOf(id)});
    }


    public void incrementSyncRetry(int id) {
        getWritableDatabase().execSQL(
                "UPDATE sync_queue SET retry_count=retry_count+1, last_retry=? WHERE id=?",
                new Object[]{System.currentTimeMillis() / 1000, id});
    }


    public int repairPunchSyncQueue() {
        int missingCount = countMissingPunchSyncQueueItems();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues pending = new ContentValues();
            pending.put("punch_state", PunchRecord.STATE_ACCEPTED);
            db.update(
                    "punch_records",
                    pending,
                    "is_synced=0 AND punch_state=?",
                    new String[]{PunchRecord.STATE_UPLOADING});

            db.execSQL(
                    "DELETE FROM sync_queue WHERE action=? AND EXISTS (" +
                            "SELECT 1 FROM punch_records p " +
                            "WHERE p.client_record_id=sync_queue.record_id " +
                            "AND p.punch_state=?)",
                    new Object[]{Constants.ACTION_PUNCH_PUSH, PunchRecord.STATE_ACCEPTING});
            db.execSQL(
                    "DELETE FROM sync_queue WHERE action=? AND EXISTS (" +
                            "SELECT 1 FROM punch_records p " +
                            "WHERE p.client_record_id=sync_queue.record_id " +
                            "AND p.punch_state<>?)",
                    new Object[]{Constants.ACTION_PUNCH_ACCEPT, PunchRecord.STATE_ACCEPTING});

            long nowSeconds = System.currentTimeMillis() / 1000L;
            db.execSQL(
                    "INSERT OR IGNORE INTO sync_queue " +
                            "(record_id, action, retry_count, created_at, last_retry) " +
                            "SELECT client_record_id, ?, 0, ?, NULL FROM punch_records " +
                            "WHERE is_synced=0 AND punch_state=? " +
                            "AND client_record_id IS NOT NULL AND TRIM(client_record_id)<>''",
                    new Object[]{
                            Constants.ACTION_PUNCH_ACCEPT,
                            nowSeconds,
                            PunchRecord.STATE_ACCEPTING
                    });
            db.execSQL(
                    "INSERT OR IGNORE INTO sync_queue " +
                            "(record_id, action, retry_count, created_at, last_retry) " +
                            "SELECT client_record_id, ?, 0, ?, NULL FROM punch_records " +
                            "WHERE is_synced=0 AND punch_state=? " +
                            "AND client_record_id IS NOT NULL AND TRIM(client_record_id)<>''",
                    new Object[]{
                            Constants.ACTION_PUNCH_PUSH,
                            nowSeconds,
                            PunchRecord.STATE_ACCEPTED
                    });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return missingCount;
    }


    public int resetLimitedPunchSyncRetries() {
        int resetCount = countLimitedPunchSyncQueueItems();
        if (resetCount <= 0) {
            return 0;
        }
        getWritableDatabase().execSQL(
                "UPDATE sync_queue SET retry_count=0, last_retry=NULL " +
                        "WHERE action=? AND retry_count>=? AND EXISTS (" +
                        "SELECT 1 FROM punch_records p " +
                        "WHERE p.client_record_id=sync_queue.record_id AND p.is_synced=0)",
                new Object[]{Constants.ACTION_PUNCH_PUSH, Constants.SYNC_MAX_RETRY});
        return resetCount;
    }

    public int resetExpiredPunchSyncRetries(long localDayStartSeconds) {
        if (localDayStartSeconds <= 0) {
            return 0;
        }
        ContentValues values = new ContentValues();
        values.put("retry_count", 0);
        values.putNull("last_retry");
        return getWritableDatabase().update(
                "sync_queue",
                values,
                "action=? AND retry_count>=? " +
                        "AND (last_retry IS NULL OR last_retry<?) " +
                        "AND EXISTS (SELECT 1 FROM punch_records p " +
                        "WHERE p.client_record_id=sync_queue.record_id AND p.is_synced=0)",
                new String[]{
                        Constants.ACTION_PUNCH_PUSH,
                        String.valueOf(Constants.SYNC_MAX_RETRY),
                        String.valueOf(localDayStartSeconds)
                });
    }


    public int getPendingCount() {
        return getUnsyncedPunchCount() + getOtherPendingQueueCount();
    }

    private int getUnsyncedPunchCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM punch_records WHERE is_synced=0", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private int getOtherPendingQueueCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sync_queue WHERE action<>?",
                new String[]{Constants.ACTION_PUNCH_PUSH});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private int countMissingPunchSyncQueueItems() {
        int count = 0;
        Cursor accepting = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM punch_records p " +
                        "WHERE p.is_synced=0 AND p.punch_state=? " +
                        "AND p.client_record_id IS NOT NULL AND TRIM(p.client_record_id)<>'' " +
                        "AND NOT EXISTS (" +
                        "SELECT 1 FROM sync_queue q " +
                        "WHERE q.action=? AND q.record_id=p.client_record_id)",
                new String[]{
                        PunchRecord.STATE_ACCEPTING,
                        Constants.ACTION_PUNCH_ACCEPT
                });
        try {
            if (accepting.moveToFirst()) {
                count += accepting.getInt(0);
            }
        } finally {
            accepting.close();
        }

        Cursor accepted = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM punch_records p " +
                        "WHERE p.is_synced=0 AND p.punch_state IN (?, ?) " +
                        "AND p.client_record_id IS NOT NULL AND TRIM(p.client_record_id)<>'' " +
                        "AND NOT EXISTS (" +
                        "SELECT 1 FROM sync_queue q " +
                        "WHERE q.action=? AND q.record_id=p.client_record_id)",
                new String[]{
                        PunchRecord.STATE_ACCEPTED,
                        PunchRecord.STATE_UPLOADING,
                        Constants.ACTION_PUNCH_PUSH
                });
        try {
            if (accepted.moveToFirst()) {
                count += accepted.getInt(0);
            }
        } finally {
            accepted.close();
        }
        return count;
    }

    private int countLimitedPunchSyncQueueItems() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sync_queue q " +
                        "WHERE q.action=? AND q.retry_count>=? AND EXISTS (" +
                        "SELECT 1 FROM punch_records p " +
                        "WHERE p.client_record_id=q.record_id AND p.is_synced=0)",
                new String[]{Constants.ACTION_PUNCH_PUSH, String.valueOf(Constants.SYNC_MAX_RETRY)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }


    public List<String> getSignedEmpIds(String date, String lineCode, int teamBindingId, int clockIndex) {
        List<String> ids = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT emp_id FROM punch_records " +
                        "WHERE punch_date=? AND line_code=? AND team_binding_id=? AND clock_index=? " +
                        "AND punch_state<>?",
                new String[]{
                        date,
                        lineCode,
                        String.valueOf(teamBindingId),
                        String.valueOf(clockIndex),
                        PunchRecord.STATE_ACCEPTING
                });
        try { while (c.moveToNext()) ids.add(c.getString(0)); }
        finally { c.close(); }
        return ids;
    }

    private void createPunchRecordsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS punch_records (" +
                "id TEXT PRIMARY KEY, client_record_id TEXT NOT NULL UNIQUE, " +
                "emp_id TEXT NOT NULL, emp_name TEXT NOT NULL, dept TEXT, " +
                "punch_time INTEGER NOT NULL, punch_date TEXT NOT NULL, " +
                "punch_type TEXT NOT NULL, shift_name TEXT, line_code TEXT NOT NULL, " +
                "team_binding_id INTEGER NOT NULL DEFAULT 0, " +
                "clock_index INTEGER NOT NULL DEFAULT 0, " +
                "match_score REAL DEFAULT 0, " +
                "snap_image_path TEXT, snap_image_mime_type TEXT DEFAULT 'image/jpeg', " +
                "snap_image_width INTEGER DEFAULT 0, snap_image_height INTEGER DEFAULT 0, " +
                "snap_image_size INTEGER DEFAULT 0, snap_captured_at INTEGER DEFAULT 0, " +
                "is_synced INTEGER DEFAULT 0, " +
                "punch_state TEXT NOT NULL DEFAULT 'ACCEPTED')");
    }

    private void createPunchRecordIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_date ON punch_records(punch_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_sync ON punch_records(is_synced)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_state ON punch_records(punch_state)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_limit " +
                "ON punch_records(punch_date, line_code, team_binding_id, clock_index, emp_id)");
    }


    public void clearLocalBusinessData() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("sync_queue", null, null);
            db.delete("punch_records", null, null);
            db.delete("face_apply_tasks", null, null);
            db.delete("face_features", null, null);
            db.delete("employees", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        PunchSnapshotHelper.clearSnapshots(appContext);
    }

    private void migrateEmployeesDropAvatarUrl(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE employees RENAME TO employees_legacy_v2");
        db.execSQL("CREATE TABLE IF NOT EXISTS employees (" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, dept TEXT, " +
                "face_image_url TEXT, face_image_sha256 TEXT, " +
                "face_version INTEGER DEFAULT 0, face_status TEXT DEFAULT 'enabled', " +
                "local_face_id TEXT, face_registered INTEGER DEFAULT 0, " +
                "assigned_line_code TEXT DEFAULT '', assigned_line_name TEXT DEFAULT '', " +
                "status TEXT DEFAULT 'normal', sync_version INTEGER DEFAULT 0, " +
                "is_deleted INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO employees (" +
                "id, name, dept, face_image_url, face_image_sha256, face_version, face_status, " +
                "local_face_id, face_registered, assigned_line_code, assigned_line_name, " +
                "status, sync_version, is_deleted, updated_at) " +
                "SELECT id, name, dept, face_image_url, face_image_sha256, face_version, face_status, " +
                "local_face_id, face_registered, assigned_line_code, assigned_line_name, " +
                "status, sync_version, is_deleted, updated_at " +
                "FROM employees_legacy_v2");
        db.execSQL("DROP TABLE employees_legacy_v2");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_emp_line ON employees(assigned_line_code)");
    }

    private void migratePunchRecordsDropTransferColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE punch_records RENAME TO punch_records_legacy_v3");
        db.execSQL("CREATE TABLE IF NOT EXISTS punch_records (" +
                "id TEXT PRIMARY KEY, client_record_id TEXT NOT NULL UNIQUE, " +
                "emp_id TEXT NOT NULL, emp_name TEXT NOT NULL, dept TEXT, " +
                "punch_time INTEGER NOT NULL, punch_date TEXT NOT NULL, " +
                "punch_index INTEGER NOT NULL, punch_type TEXT NOT NULL, " +
                "shift_name TEXT, line_code TEXT NOT NULL, line_name TEXT NOT NULL, " +
                "device_id TEXT NOT NULL, status TEXT DEFAULT 'normal', " +
                "is_early INTEGER DEFAULT 0, is_synced INTEGER DEFAULT 0, " +
                "sync_time INTEGER, server_id TEXT)");
        db.execSQL("INSERT INTO punch_records (" +
                "id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_early, is_synced, sync_time, server_id) " +
                "SELECT id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_early, is_synced, sync_time, server_id " +
                "FROM punch_records_legacy_v3");
        db.execSQL("DROP TABLE punch_records_legacy_v3");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_date ON punch_records(punch_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_sync ON punch_records(is_synced)");
    }

    private void migratePunchRecordsDropIsEarly(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE punch_records RENAME TO punch_records_legacy_v4");
        db.execSQL("CREATE TABLE IF NOT EXISTS punch_records (" +
                "id TEXT PRIMARY KEY, client_record_id TEXT NOT NULL UNIQUE, " +
                "emp_id TEXT NOT NULL, emp_name TEXT NOT NULL, dept TEXT, " +
                "punch_time INTEGER NOT NULL, punch_date TEXT NOT NULL, " +
                "punch_index INTEGER NOT NULL, punch_type TEXT NOT NULL, " +
                "shift_name TEXT, line_code TEXT NOT NULL, line_name TEXT NOT NULL, " +
                "device_id TEXT NOT NULL, status TEXT DEFAULT 'normal', " +
                "is_synced INTEGER DEFAULT 0, sync_time INTEGER, server_id TEXT)");
        db.execSQL("INSERT INTO punch_records (" +
                "id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_synced, sync_time, server_id) " +
                "SELECT id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_index, punch_type, shift_name, line_code, line_name, device_id, " +
                "status, is_synced, sync_time, server_id " +
                "FROM punch_records_legacy_v4");
        db.execSQL("DROP TABLE punch_records_legacy_v4");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_date ON punch_records(punch_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punch_sync ON punch_records(is_synced)");
    }

    private void migratePunchRecordsSlimColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE punch_records RENAME TO punch_records_legacy_v5");
        createPunchRecordsTable(db);
        db.execSQL("INSERT INTO punch_records (" +
                "id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_type, shift_name, line_code, is_synced) " +
                "SELECT id, client_record_id, emp_id, emp_name, dept, punch_time, punch_date, " +
                "punch_type, shift_name, line_code, is_synced " +
                "FROM punch_records_legacy_v5");
        db.execSQL("DROP TABLE punch_records_legacy_v5");
        createPunchRecordIndexes(db);
    }

    private void migratePunchRecordsAddTeamBindingAndClockIndex(SQLiteDatabase db) {
        db.execSQL("DROP INDEX IF EXISTS idx_punch_date");
        db.execSQL("DROP INDEX IF EXISTS idx_punch_sync");
        db.execSQL("DROP INDEX IF EXISTS idx_punch_limit");
        addColumnIfMissing(db, "punch_records", "team_binding_id", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "clock_index", "INTEGER NOT NULL DEFAULT 0");
        createPunchRecordIndexes(db);
        ensureSyncQueueTable(db);
        repairPunchSyncQueue(db);
    }

    private void migratePunchRecordsAddSnapshotColumns(SQLiteDatabase db) {
        addColumnIfMissing(db, "punch_records", "match_score", "REAL DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_image_path", "TEXT");
        addColumnIfMissing(db, "punch_records", "snap_image_mime_type", "TEXT DEFAULT 'image/jpeg'");
        addColumnIfMissing(db, "punch_records", "snap_image_width", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_image_height", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_image_size", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "punch_records", "snap_captured_at", "INTEGER DEFAULT 0");
    }

    private void repairPunchSyncQueue(SQLiteDatabase db) {
        db.execSQL(
                "INSERT OR IGNORE INTO sync_queue (record_id, action, retry_count, created_at, last_retry) " +
                        "SELECT client_record_id, ?, 0, ?, NULL FROM punch_records " +
                        "WHERE is_synced=0 AND client_record_id IS NOT NULL AND TRIM(client_record_id)<>''",
                new Object[]{Constants.ACTION_PUNCH_PUSH, System.currentTimeMillis() / 1000});
    }

    private void ensureSyncQueueTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "record_id TEXT NOT NULL, action TEXT NOT NULL, " +
                "retry_count INTEGER DEFAULT 0, created_at INTEGER NOT NULL DEFAULT 0, " +
                "last_retry INTEGER, UNIQUE(action, record_id))");
        addColumnIfMissing(db, "sync_queue", "retry_count", "INTEGER DEFAULT 0");
        addColumnIfMissing(db, "sync_queue", "created_at", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "sync_queue", "last_retry", "INTEGER");
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        if (!hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) {
                String existing = c.getString(c.getColumnIndexOrThrow("name"));
                if (column.equalsIgnoreCase(existing)) {
                    return true;
                }
            }
            return false;
        } finally {
            c.close();
        }
    }

    public int countPunchRecordsByClientPrefix(String prefix) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM punch_records WHERE client_record_id LIKE ?",
                new String[]{prefix + "%"});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public int countSyncQueueByAction(String action) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sync_queue WHERE action=?",
                new String[]{action});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public int deleteSyncQueueByAction(String action) {
        return getWritableDatabase().delete("sync_queue", "action=?", new String[]{action});
    }

    public int deletePunchRecordsByClientPrefix(String prefix) {
        return getWritableDatabase().delete(
                "punch_records", "client_record_id LIKE ?", new String[]{prefix + "%"});
    }

}
