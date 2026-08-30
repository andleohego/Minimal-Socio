package cl.andres.ordenafotos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CleanupService extends Service {
    public static final String PREFS = "cleanup_v124";
    public static final String ACTION_START = "cl.andres.ordenafotos.CLEANUP_START";
    public static final String ACTION_STOP = "cl.andres.ordenafotos.CLEANUP_STOP";
    public static final String EXTRA_RESET = "reset";

    public static final String K_RUNNING = "running";
    public static final String K_COMPLETE = "complete";
    public static final String K_SCANNED = "scanned";
    public static final String K_TOTAL = "total";
    public static final String K_VERIFIED = "verified";
    public static final String K_BYTES = "bytes";
    public static final String K_LAST_ID = "last_id";
    public static final String K_STATUS = "status";
    public static final String K_FAILED = "failed";

    private static final String CHANNEL_ID = "cleanup_verify_v124";
    private static final int NOTIFICATION_ID = 4424;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean workerActive = false;
    private volatile boolean stopRequested = false;
    private SharedPreferences prefs;
    private AnalysisDb db;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        db = new AnalysisDb(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            prefs.edit().putBoolean(K_RUNNING, false)
                    .putString(K_STATUS, "Verificación detenida. Puedes reanudarla.").apply();
            updateNotification();
            return START_NOT_STICKY;
        }

        if (workerActive) return START_STICKY;
        boolean reset = intent != null && intent.getBooleanExtra(EXTRA_RESET, false);
        if (action == null && !prefs.getBoolean(K_RUNNING, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        stopRequested = false;
        ensureForeground();
        acquireWakeLock();
        workerActive = true;
        executor.execute(() -> {
            try {
                verifyDuplicates(reset);
            } finally {
                workerActive = false;
                releaseWakeLock();
            }
        });
        return START_STICKY;
    }

    private void verifyDuplicates(boolean reset) {
        if (reset) {
            db.clearCleanupVerified();
            prefs.edit()
                    .putBoolean(K_COMPLETE, false)
                    .putInt(K_SCANNED, 0)
                    .putInt(K_TOTAL, 0)
                    .putInt(K_VERIFIED, 0)
                    .putLong(K_BYTES, 0L)
                    .putLong(K_LAST_ID, 0L)
                    .putInt(K_FAILED, 0)
                    .putString(K_STATUS, "Preparando verificación…")
                    .apply();
        }

        prefs.edit().putBoolean(K_RUNNING, true).putBoolean(K_COMPLETE, false).apply();
        updateNotification();

        try {
            List<MediaEntry> originals = loadEntries("Android/media/%");
            List<MediaEntry> copies = loadEntries("Pictures/OrdenaFotos/%");
            originals.sort((a, b) -> Long.compare(a.id, b.id));

            Map<String, ArrayList<MediaEntry>> copyMap = new HashMap<>();
            for (MediaEntry copy : copies) {
                if (copy.name.isEmpty() || copy.size <= 0) continue;
                String key = key(copy.name, copy.size);
                copyMap.computeIfAbsent(key, k -> new ArrayList<>()).add(copy);
            }

            long lastId = reset ? 0L : prefs.getLong(K_LAST_ID, 0L);
            int scanned = reset ? 0 : prefs.getInt(K_SCANNED, 0);
            int total = originals.size();
            int verified = db.countCleanupVerified();
            long bytes = db.sumCleanupVerifiedBytes();
            int failed = prefs.getInt(K_FAILED, 0);
            Map<Long, String> copyHashCache = new HashMap<>();

            prefs.edit().putInt(K_TOTAL, total)
                    .putString(K_STATUS, "Comparando originales con copias mediante SHA-256…")
                    .apply();
            updateNotification();

            for (MediaEntry original : originals) {
                if (stopRequested) {
                    prefs.edit().putBoolean(K_RUNNING, false)
                            .putString(K_STATUS, "Verificación detenida. Avance guardado.").apply();
                    updateNotification();
                    stopForeground(STOP_FOREGROUND_DETACH);
                    stopSelf();
                    return;
                }
                if (original.id <= lastId) continue;

                try {
                    ArrayList<MediaEntry> candidates = copyMap.get(key(original.name, original.size));
                    if (candidates != null && !candidates.isEmpty() && original.size > 0) {
                        String originalHash = sha256(original.uri);
                        for (MediaEntry candidate : candidates) {
                            String candidateHash = copyHashCache.get(candidate.id);
                            if (candidateHash == null) {
                                candidateHash = sha256(candidate.uri);
                                copyHashCache.put(candidate.id, candidateHash);
                            }
                            if (originalHash.equals(candidateHash)) {
                                db.addCleanupVerified(original.id, candidate.id, original.size, originalHash);
                                verified++;
                                bytes += original.size;
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    failed++;
                }

                scanned++;
                lastId = original.id;
                if (scanned == 1 || scanned % 5 == 0 || scanned >= total) {
                    prefs.edit()
                            .putInt(K_SCANNED, scanned)
                            .putInt(K_TOTAL, total)
                            .putInt(K_VERIFIED, verified)
                            .putLong(K_BYTES, bytes)
                            .putLong(K_LAST_ID, lastId)
                            .putInt(K_FAILED, failed)
                            .putString(K_STATUS, "Verificando " + scanned + " de " + total + "…")
                            .apply();
                }
                if (scanned == 1 || scanned % 20 == 0 || scanned >= total) updateNotification();
            }

            int finalVerified = db.countCleanupVerified();
            long finalBytes = db.sumCleanupVerifiedBytes();
            prefs.edit()
                    .putBoolean(K_RUNNING, false)
                    .putBoolean(K_COMPLETE, true)
                    .putInt(K_SCANNED, total)
                    .putInt(K_TOTAL, total)
                    .putInt(K_VERIFIED, finalVerified)
                    .putLong(K_BYTES, finalBytes)
                    .putString(K_STATUS, "Verificación terminada: " + finalVerified + " originales tienen copia idéntica.")
                    .apply();
            updateNotification();
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        } catch (Exception e) {
            String msg = e.getMessage();
            prefs.edit().putBoolean(K_RUNNING, false)
                    .putString(K_STATUS, "Error de verificación: " + e.getClass().getSimpleName() +
                            (msg == null || msg.isEmpty() ? "" : " — " + msg)).apply();
            updateNotification();
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private List<MediaEntry> loadEntries(String relativePathLike) {
        ArrayList<MediaEntry> out = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.RELATIVE_PATH
        };
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {relativePathLike};
        try (Cursor c = getContentResolver().query(collection, projection, selection, args,
                MediaStore.Images.Media._ID + " ASC")) {
            if (c == null) return out;
            int idIx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int sizeIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int pathIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
            while (c.moveToNext()) {
                long id = c.getLong(idIx);
                String name = c.isNull(nameIx) ? "" : c.getString(nameIx);
                long size = c.isNull(sizeIx) ? 0L : c.getLong(sizeIx);
                String path = c.isNull(pathIx) ? "" : c.getString(pathIx);
                out.add(new MediaEntry(id, ContentUris.withAppendedId(collection, id), name, size, path));
            }
        }
        return out;
    }

    private String key(String name, long size) {
        return (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "\u0000" + size;
    }

    private String sha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("No pude abrir " + uri);
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "Verificación de duplicados", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Comprueba copias idénticas antes de limpiar originales");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void ensureForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        int scanned = prefs == null ? 0 : prefs.getInt(K_SCANNED, 0);
        int total = prefs == null ? 0 : prefs.getInt(K_TOTAL, 0);
        int verified = prefs == null ? 0 : prefs.getInt(K_VERIFIED, 0);
        boolean running = prefs != null && prefs.getBoolean(K_RUNNING, false);
        String text = running
                ? "Revisando " + scanned + " de " + total + " • idénticas: " + verified
                : (prefs == null ? "Preparando…" : prefs.getString(K_STATUS, "Verificación detenida"));

        Intent open = new Intent(this, CleanupActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 41, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, CleanupService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 42, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Limpieza OrdenaFotos")
                .setContentText(text)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(running)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (running) b.addAction(new Notification.Action.Builder(null, "Detener", stopPi).build());
        if (total > 0) b.setProgress(total, Math.min(scanned, total), false);
        else if (running) b.setProgress(0, 0, true);
        return b.build();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrdenaFotos:CleanupVerify");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        executor.shutdownNow();
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class MediaEntry {
        final long id;
        final Uri uri;
        final String name;
        final long size;
        final String path;
        MediaEntry(long id, Uri uri, String name, long size, String path) {
            this.id = id;
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.size = size;
            this.path = path == null ? "" : path;
        }
    }
}
