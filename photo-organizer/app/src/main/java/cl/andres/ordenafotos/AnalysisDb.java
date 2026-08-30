package cl.andres.ordenafotos;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AnalysisDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "ordenafotos.db";
    private static final int DB_VERSION = 2;

    public AnalysisDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE results (" +
                "media_id INTEGER PRIMARY KEY," +
                "uri TEXT NOT NULL," +
                "display_name TEXT," +
                "relative_path TEXT," +
                "date_taken INTEGER," +
                "category TEXT NOT NULL," +
                "confidence REAL NOT NULL," +
                "selected INTEGER NOT NULL," +
                "reason TEXT," +
                "analyzed_at INTEGER NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX idx_results_category ON results(category)");
        db.execSQL("CREATE INDEX idx_results_selected ON results(selected)");
        createCleanupTable(db);
    }

    private void createCleanupTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cleanup_verified (" +
                "media_id INTEGER PRIMARY KEY," +
                "bytes INTEGER NOT NULL," +
                "verified_at INTEGER NOT NULL" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createCleanupTable(db);
    }

    public synchronized void save(ResultRow row) {
        ContentValues v = new ContentValues();
        v.put("media_id", row.mediaId);
        v.put("uri", row.uri);
        v.put("display_name", row.name);
        v.put("relative_path", row.path);
        v.put("date_taken", row.taken);
        v.put("category", row.category);
        v.put("confidence", row.confidence);
        v.put("selected", row.selected ? 1 : 0);
        v.put("reason", row.reason);
        v.put("analyzed_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("results", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Set<Long> loadProcessedIds() {
        HashSet<Long> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT media_id FROM results", null)) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    public synchronized int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM results", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int countSelected() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM results WHERE selected=1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized Map<String, Integer> categoryCounts() {
        HashMap<String, Integer> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, COUNT(*) FROM results GROUP BY category ORDER BY COUNT(*) DESC", null)) {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        }
        return out;
    }

    public synchronized List<ResultRow> latest(int limit) {
        ArrayList<ResultRow> out = new ArrayList<>();
        String sql = "SELECT media_id,uri,display_name,relative_path,date_taken,category,confidence,selected,reason " +
                "FROM results ORDER BY analyzed_at DESC LIMIT " + Math.max(1, Math.min(limit, 500));
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    public synchronized List<ResultRow> allRows() {
        ArrayList<ResultRow> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT media_id,uri,display_name,relative_path,date_taken,category,confidence,selected,reason " +
                        "FROM results ORDER BY media_id", null)) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    public synchronized List<ResultRow> selectedRows() {
        ArrayList<ResultRow> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT media_id,uri,display_name,relative_path,date_taken,category,confidence,selected,reason " +
                        "FROM results WHERE selected=1 AND category <> 'Sin clasificar' ORDER BY media_id", null)) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    public synchronized void setSelected(long mediaId, boolean selected) {
        ContentValues v = new ContentValues();
        v.put("selected", selected ? 1 : 0);
        getWritableDatabase().update("results", v, "media_id=?", new String[]{Long.toString(mediaId)});
    }

    public synchronized void setCategory(long mediaId, String category, boolean selected) {
        ContentValues v = new ContentValues();
        v.put("category", category);
        v.put("selected", selected ? 1 : 0);
        getWritableDatabase().update("results", v, "media_id=?", new String[]{Long.toString(mediaId)});
    }

    /**
     * Reconciles rows with MediaStore after an older version already moved photos.
     * Returns how many rows were actually removed from the pending queue.
     */
    public synchronized int markOrganizedIds(List<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) return 0;
        SQLiteDatabase database = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("selected", 0);
        int changed = 0;
        database.beginTransaction();
        try {
            for (Long id : mediaIds) {
                if (id == null) continue;
                changed += database.update(
                        "results", v,
                        "media_id=? AND selected=1",
                        new String[]{Long.toString(id)}
                );
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return changed;
    }

    public synchronized void clearCleanupVerified() {
        getWritableDatabase().delete("cleanup_verified", null, null);
    }

    public synchronized void addCleanupVerified(long mediaId, long bytes) {
        ContentValues v = new ContentValues();
        v.put("media_id", mediaId);
        v.put("bytes", Math.max(0L, bytes));
        v.put("verified_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("cleanup_verified", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized int countCleanupVerified() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM cleanup_verified", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized long sumCleanupVerifiedBytes() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(bytes),0) FROM cleanup_verified", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    public synchronized List<CleanupVerified> cleanupVerifiedRows() {
        ArrayList<CleanupVerified> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT media_id,bytes FROM cleanup_verified ORDER BY media_id", null)) {
            while (c.moveToNext()) out.add(new CleanupVerified(c.getLong(0), c.getLong(1)));
        }
        return out;
    }

    public synchronized void removeCleanupVerified(List<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) return;
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            for (Long id : mediaIds) {
                if (id == null) continue;
                database.delete("cleanup_verified", "media_id=?", new String[]{Long.toString(id)});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized void clearAll() {
        SQLiteDatabase database = getWritableDatabase();
        database.delete("cleanup_verified", null, null);
        database.delete("results", null, null);
    }

    private ResultRow fromCursor(Cursor c) {
        return new ResultRow(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4),
                c.getString(5), c.getFloat(6), c.getInt(7) == 1, c.getString(8)
        );
    }

    public static class CleanupVerified {
        public final long mediaId;
        public final long bytes;
        public CleanupVerified(long mediaId, long bytes) {
            this.mediaId = mediaId;
            this.bytes = bytes;
        }
    }

    public static class ResultRow {
        public final long mediaId;
        public final String uri;
        public final String name;
        public final String path;
        public final long taken;
        public final String category;
        public final float confidence;
        public final boolean selected;
        public final String reason;

        public ResultRow(long mediaId, String uri, String name, String path, long taken,
                         String category, float confidence, boolean selected, String reason) {
            this.mediaId = mediaId;
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.path = path == null ? "" : path;
            this.taken = taken;
            this.category = category == null ? "Sin clasificar" : category;
            this.confidence = confidence;
            this.selected = selected;
            this.reason = reason == null ? "" : reason;
        }
    }
}
