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
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Size;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalysisService extends Service {
    public static final String PREFS = "analysis_state";
    public static final String ACTION_START = "cl.andres.ordenafotos.START";
    public static final String ACTION_PAUSE = "cl.andres.ordenafotos.PAUSE";
    public static final String ACTION_RESUME = "cl.andres.ordenafotos.RESUME";
    public static final String ACTION_STOP = "cl.andres.ordenafotos.STOP";
    public static final String ACTION_CLEAR_AND_START = "cl.andres.ordenafotos.CLEAR_AND_START";

    private static final String CHANNEL_ID = "photo_analysis";
    private static final int NOTIFICATION_ID = 4111;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean workerActive = false;
    private volatile boolean paused = false;
    private volatile boolean stopRequested = false;

    private SharedPreferences prefs;
    private AnalysisDb db;
    private ImageLabeler imageLabeler;
    private TextRecognizer textRecognizer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        db = new AnalysisDb(this);
        imageLabeler = ImageLabeling.getClient(
                new ImageLabelerOptions.Builder().setConfidenceThreshold(0.50f).build());
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            paused = false;
            prefs.edit().putBoolean("running", false).putBoolean("paused", false)
                    .putString("status", "Análisis detenido. Puedes continuarlo después.").apply();
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE.equals(action)) {
            paused = true;
            prefs.edit().putBoolean("paused", true)
                    .putString("status", "Análisis pausado. El avance quedó guardado.").apply();
            releaseWakeLock();
            updateNotification();
            return START_STICKY;
        }

        if (ACTION_RESUME.equals(action)) {
            paused = false;
            stopRequested = false;
            prefs.edit().putBoolean("running", true).putBoolean("paused", false)
                    .putString("status", "Reanudando análisis…").apply();
            acquireWakeLock();
            ensureForeground();
            startWorkerIfNeeded();
            return START_STICKY;
        }

        if (ACTION_CLEAR_AND_START.equals(action)) {
            db.clearAll();
            prefs.edit().clear().apply();
        }

        if (action == null && !prefs.getBoolean("running", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        paused = prefs.getBoolean("paused", false);
        stopRequested = false;
        prefs.edit().putBoolean("running", true)
                .putString("status", paused ? "Análisis pausado." : "Preparando análisis…")
                .putLong("started_at", prefs.getLong("started_at", System.currentTimeMillis()))
                .apply();
        ensureForeground();
        if (!paused) acquireWakeLock();
        startWorkerIfNeeded();
        return START_STICKY;
    }

    private void startWorkerIfNeeded() {
        if (workerActive) return;
        workerActive = true;
        executor.execute(() -> {
            try {
                runAnalysis();
            } finally {
                workerActive = false;
            }
        });
    }

    private void runAnalysis() {
        List<MediaRef> refs = loadGallery();
        int total = refs.size();
        Set<Long> processed = db.loadProcessedIds();
        int done = 0;
        for (MediaRef ref : refs) if (processed.contains(ref.id)) done++;

        prefs.edit().putInt("total", total).putInt("done", done)
                .putString("status", total == 0 ? "No encontré fotos accesibles." : "Analizando galería…")
                .apply();
        updateNotification();

        if (total == 0) {
            finishNormally("No encontré fotos accesibles. Revisa el permiso de Fotos y videos.");
            return;
        }

        for (MediaRef ref : refs) {
            if (stopRequested) return;

            while (paused && !stopRequested) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            if (stopRequested) return;
            acquireWakeLock();

            if (processed.contains(ref.id)) continue;

            AnalysisDb.ResultRow row = analyzePhoto(ref);
            db.save(row);
            processed.add(ref.id);
            done++;

            if (done == 1 || done % 5 == 0 || done == total) {
                prefs.edit().putInt("done", done).putInt("total", total)
                        .putString("status", "Analizando " + done + " de " + total + "…")
                        .putLong("last_media_id", ref.id).apply();
            }
            if (done == 1 || done % 10 == 0 || done == total) updateNotification();
        }

        finishNormally("Análisis terminado. Los resultados quedaron guardados.");
    }

    private void finishNormally(String message) {
        prefs.edit().putBoolean("running", false).putBoolean("paused", false)
                .putInt("done", Math.max(prefs.getInt("done", 0), db.count()))
                .putString("status", message).putLong("finished_at", System.currentTimeMillis()).apply();
        releaseWakeLock();
        updateNotification();
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private List<MediaRef> loadGallery() {
        ArrayList<MediaRef> refs = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED
        };
        try (Cursor c = getContentResolver().query(collection, projection, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) return refs;
            int idIx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int pathIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
            int takenIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int addedIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            while (c.moveToNext()) {
                long id = c.getLong(idIx);
                String name = safe(c.getString(nameIx));
                String path = safe(c.getString(pathIx));
                if (normalize(path).contains("pictures/ordenafotos")) continue;
                long taken = c.isNull(takenIx) ? 0L : c.getLong(takenIx);
                if (taken <= 0 && !c.isNull(addedIx)) taken = c.getLong(addedIx) * 1000L;
                refs.add(new MediaRef(id, ContentUris.withAppendedId(collection, id), name, path, taken));
            }
        } catch (Exception e) {
            prefs.edit().putString("status", "No pude leer la galería: " + e.getClass().getSimpleName())
                    .putString("last_error", safe(e.getMessage())).apply();
        }
        return refs;
    }

    private AnalysisDb.ResultRow analyzePhoto(MediaRef ref) {
        String combined = normalize(ref.path + " " + ref.name);
        boolean screenshot = containsAny(combined, "screenshot", "screenshots", "captura de pantalla");
        boolean whatsapp = containsAny(combined, "whatsapp", "whatsapp images", "wa0", "-wa");

        if (containsAny(combined, "pedidosya", "pedidos ya", "peya", "zubale", "uber", "rappi", "delivery", "reparto", "despacho")) {
            return row(ref, "Trabajo", 1f, true, "regla por nombre/carpeta");
        }
        if (containsAny(combined, "boleta", "factura", "receipt", "invoice", "comprobante", "documento", "scan", "scanner")) {
            return row(ref, "Documentos", 1f, true, "regla por nombre/carpeta");
        }

        Bitmap bitmap = null;
        try {
            int side = screenshot ? 1080 : 640;
            bitmap = getContentResolver().loadThumbnail(ref.uri, new Size(side, side), null);
            InputImage input = InputImage.fromBitmap(bitmap, 0);

            if (screenshot) {
                try {
                    Text text = Tasks.await(textRecognizer.process(input));
                    String ocr = normalize(text == null ? "" : text.getText());
                    if (isWorkText(ocr)) return row(ref, "Trabajo", 0.99f, true, "OCR de captura");
                    if (isDocumentText(ocr)) return row(ref, "Documentos", 0.94f, true, "OCR de captura");
                } catch (Exception ignored) { }
                return row(ref, "Capturas", 0.92f, true, "carpeta de capturas");
            }

            List<ImageLabel> labels = Tasks.await(imageLabeler.process(input));
            Decision decision = decideFromLabels(labels);
            if (decision != null) {
                boolean selected = decision.score >= 0.72f;
                return row(ref, decision.category, decision.score, selected, "IA local");
            }
            if (whatsapp) return row(ref, "WhatsApp", 0.82f, true, "origen WhatsApp");
            return row(ref, "Sin clasificar", 0f, false, "sin coincidencia segura");
        } catch (Exception e) {
            if (whatsapp) return row(ref, "WhatsApp", 0.70f, false, "origen WhatsApp; IA no disponible");
            return row(ref, "Sin clasificar", 0f, false, "no se pudo analizar");
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private AnalysisDb.ResultRow row(MediaRef ref, String category, float confidence, boolean selected, String reason) {
        return new AnalysisDb.ResultRow(ref.id, ref.uri.toString(), ref.name, ref.path, ref.taken,
                category, confidence, selected, reason);
    }

    private Decision decideFromLabels(List<ImageLabel> labels) {
        if (labels == null || labels.isEmpty()) return null;
        Map<String, Float> score = new HashMap<>();
        for (ImageLabel label : labels) {
            String t = normalize(label.getText());
            float c = label.getConfidence();
            addIf(score, "Autos", c * 1.22f, t,
                    "car", "vehicle", "automotive", "wheel", "tire", "tyre", "truck", "bus", "motorcycle", "engine", "bumper", "headlight", "auto", "vehiculo");
            addIf(score, "Mascotas", c * 1.18f, t,
                    "cat", "dog", "pet", "puppy", "kitten", "animal", "bird", "horse", "gato", "perro", "mascota");
            addIf(score, "Documentos", c * 1.16f, t,
                    "document", "paper", "receipt", "text", "handwriting", "form", "documento", "papel", "recibo");
            addIf(score, "Comida", c * 1.10f, t,
                    "food", "dish", "meal", "cuisine", "fruit", "vegetable", "drink", "comida", "plato", "fruta");
            addIf(score, "Paisajes", c * 1.04f, t,
                    "landscape", "nature", "mountain", "beach", "forest", "ocean", "lake", "sunset", "sky", "park", "paisaje", "naturaleza");
            addIf(score, "Personas", c * 0.88f, t,
                    "person", "people", "human", "face", "selfie", "portrait", "child", "man", "woman", "persona", "rostro");
        }

        String best = null;
        float bestScore = 0f;
        float second = 0f;
        for (Map.Entry<String, Float> e : score.entrySet()) {
            float s = e.getValue();
            if (s > bestScore) {
                second = bestScore;
                bestScore = s;
                best = e.getKey();
            } else if (s > second) {
                second = s;
            }
        }
        if (best == null || bestScore < 0.60f) return null;
        if (bestScore < 0.74f && bestScore - second < 0.05f) return null;
        return new Decision(best, Math.min(1f, bestScore));
    }

    private void addIf(Map<String, Float> scores, String category, float value, String label, String... keys) {
        for (String key : keys) {
            if (label.contains(key)) {
                Float old = scores.get(category);
                if (old == null || value > old) scores.put(category, value);
                return;
            }
        }
    }

    private boolean isWorkText(String s) {
        return containsAny(s, "pedidos ya", "pedidosya", "peya", "zubale", "uber", "rappi", "reparto", "delivery", "pedido", "ruta", "despacho");
    }

    private boolean isDocumentText(String s) {
        return containsAny(s, "boleta", "factura", "total", "rut", "subtotal", "iva", "comprobante", "recibo", "transferencia");
    }

    private boolean containsAny(String source, String... keys) {
        for (String key : keys) if (source.contains(normalize(key))) return true;
        return false;
    }

    private String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    private String safe(String s) { return s == null ? "" : s; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Análisis de fotos",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantiene el análisis activo con la pantalla bloqueada");
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
        int done = prefs.getInt("done", 0);
        int total = prefs.getInt("total", 0);
        boolean isPaused = prefs.getBoolean("paused", false) || paused;
        boolean isRunning = prefs.getBoolean("running", false);
        String text;
        if (!isRunning && total > 0 && done >= total) text = "Análisis terminado: " + done + " fotos";
        else if (!isRunning) text = "Análisis detenido — avance guardado";
        else if (isPaused) text = "Pausado — avance guardado";
        else text = total > 0 ? "Analizando " + done + " de " + total : "Preparando galería…";

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggle = new Intent(this, AnalysisService.class)
                .setAction(isPaused ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent togglePi = PendingIntent.getService(this, 11, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, AnalysisService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 12, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("OrdenaFotos Socio")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(isRunning && !isPaused)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (isRunning) {
            b.addAction(new Notification.Action.Builder(null, isPaused ? "Continuar" : "Pausar", togglePi).build())
                    .addAction(new Notification.Action.Builder(null, "Detener", stopPi).build());
        }
        if (total > 0) b.setProgress(total, Math.min(done, total), false);
        else if (isRunning) b.setProgress(0, 0, true);
        return b.build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrdenaFotos:AnalysisWakeLock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        try { imageLabeler.close(); } catch (Exception ignored) { }
        try { textRecognizer.close(); } catch (Exception ignored) { }
        executor.shutdownNow();
        db.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private static class MediaRef {
        final long id;
        final Uri uri;
        final String name;
        final String path;
        final long taken;
        MediaRef(long id, Uri uri, String name, String path, long taken) {
            this.id = id; this.uri = uri; this.name = name; this.path = path; this.taken = taken;
        }
    }

    private static class Decision {
        final String category;
        final float score;
        Decision(String category, float score) { this.category = category; this.score = score; }
    }
}
