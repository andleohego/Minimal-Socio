package cl.andres.ordenafotos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CleanupActivity extends Activity {
    private static final int REQ_READ = 2400;
    private static final int REQ_DELETE = 2401;
    private static final int DELETE_BATCH = 1900;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<ArrayList<DeleteItem>> deleteBatches = new ArrayList<>();
    private SharedPreferences prefs;
    private AnalysisDb db;

    private ProgressBar progress;
    private TextView status;
    private TextView result;
    private Button verifyButton;
    private Button stopButton;
    private Button deleteButton;
    private Button mainButton;

    private boolean preparingDelete = false;
    private boolean deleting = false;
    private int deleteBatchIndex = 0;
    private int deletedCount = 0;
    private long deletedBytes = 0L;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(CleanupService.PREFS, MODE_PRIVATE);
        db = new AnalysisDb(this);
        buildUi();
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        executor.shutdownNow();
        if (db != null) db.close();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0xFFF5F7F8);

        TextView title = text("Limpieza OrdenaFotos v1.2.4", 25, 0xFF102027, true);
        root.addView(title);
        TextView subtitle = text("Verificación exacta SHA-256 antes de borrar originales", 14, 0xFF455A64, false);
        subtitle.setPadding(0, dp(5), 0, dp(14));
        root.addView(subtitle);

        TextView safety = text("Primero compara cada original de Android/media con su copia en Pictures/OrdenaFotos. Solo los archivos idénticos quedan habilitados para limpiar.", 14, 0xFF37474F, false);
        safety.setPadding(0, 0, 0, dp(12));
        root.addView(safety);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));

        status = text("Preparando…", 14, 0xFF263238, true);
        status.setPadding(0, dp(10), 0, dp(6));
        root.addView(status);

        result = text("", 14, 0xFF00695C, false);
        result.setPadding(0, 0, 0, dp(14));
        root.addView(result);

        verifyButton = button("VERIFICAR DUPLICADOS", v -> startVerification());
        root.addView(verifyButton, full());

        stopButton = button("DETENER VERIFICACIÓN", v -> stopVerification());
        root.addView(stopButton, full());

        deleteButton = button("ELIMINAR ORIGINALES VERIFICADOS", v -> prepareDelete());
        root.addView(deleteButton, full());

        mainButton = button("ABRIR ORDENAFOTOS", v -> {
            Intent i = new Intent(this, MainActivity.class);
            startActivity(i);
        });
        root.addView(mainButton, full());

        TextView note = text("La eliminación siempre pasa por la confirmación oficial de Android. Las copias dentro de OrdenaFotos no se incluyen en la solicitud de borrado.", 12, 0xFF607D8B, false);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        setContentView(root);
    }

    private void startVerification() {
        if (deleting || preparingDelete) return;
        if (!hasPhotoPermission()) {
            requestPhotoPermission();
            return;
        }
        boolean running = prefs.getBoolean(CleanupService.K_RUNNING, false);
        if (running) {
            Toast.makeText(this, "La verificación ya está funcionando.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean complete = prefs.getBoolean(CleanupService.K_COMPLETE, false);
        int scanned = prefs.getInt(CleanupService.K_SCANNED, 0);
        if (!complete && scanned > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Reanudar verificación")
                    .setMessage("Hay avance guardado. Continuaré desde donde quedó.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Reanudar", (d, w) -> startCleanupService(false))
                    .show();
        } else if (complete) {
            new AlertDialog.Builder(this)
                    .setTitle("¿Verificar nuevamente?")
                    .setMessage("Se borrará solo la lista interna de comprobaciones anteriores y se recalcularán los SHA-256. No se borrará ninguna foto.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Verificar de nuevo", (d, w) -> startCleanupService(true))
                    .show();
        } else {
            startCleanupService(true);
        }
    }

    private void startCleanupService(boolean reset) {
        Intent i = new Intent(this, CleanupService.class)
                .setAction(CleanupService.ACTION_START)
                .putExtra(CleanupService.EXTRA_RESET, reset);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        requestNotificationPermissionIfNeeded();
        refreshState();
    }

    private void stopVerification() {
        if (!prefs.getBoolean(CleanupService.K_RUNNING, false)) return;
        Intent i = new Intent(this, CleanupService.class).setAction(CleanupService.ACTION_STOP);
        startService(i);
    }

    private void prepareDelete() {
        if (preparingDelete || deleting || prefs.getBoolean(CleanupService.K_RUNNING, false)) return;
        if (!hasPhotoPermission()) {
            requestPhotoPermission();
            return;
        }
        int verified = db.countCleanupVerified();
        if (verified <= 0) {
            Toast.makeText(this, "Primero verifica los duplicados.", Toast.LENGTH_LONG).show();
            return;
        }

        preparingDelete = true;
        refreshState();
        executor.execute(() -> {
            ArrayList<DeleteItem> safe = buildSafeDeleteList();
            runOnUiThread(() -> {
                preparingDelete = false;
                if (isFinishing()) return;
                refreshState();
                if (safe.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("No hay originales listos para borrar")
                            .setMessage("Ningún original pasó la comprobación final de existencia, ruta y tamaño. No se borró nada.")
                            .setPositiveButton("Entendido", null)
                            .show();
                    return;
                }
                long bytes = 0L;
                for (DeleteItem item : safe) bytes += item.bytes;
                final long safeBytes = bytes;
                new AlertDialog.Builder(this)
                        .setTitle("Liberar " + formatBytes(safeBytes))
                        .setMessage("Voy a solicitar a Android borrar " + safe.size() + " originales de Android/media.\n\n" +
                                "Cada uno tiene una copia previamente verificada por SHA-256 en Pictures/OrdenaFotos y esa copia sigue existiendo con el mismo tamaño.\n\n" +
                                "Las copias ordenadas NO se borrarán. Android pedirá tu confirmación por lotes.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Continuar", (d, w) -> beginDeleteBatches(safe))
                        .show();
            });
        });
    }

    private ArrayList<DeleteItem> buildSafeDeleteList() {
        ArrayList<DeleteItem> out = new ArrayList<>();
        List<AnalysisDb.CleanupVerified> verified = db.cleanupVerifiedRows();
        if (verified.isEmpty()) return out;

        HashMap<Long, MediaMeta> originals = new HashMap<>();
        HashMap<Long, MediaMeta> copies = new HashMap<>();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.RELATIVE_PATH
        };
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? OR " +
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Android/media/%", "Pictures/OrdenaFotos/%"};
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, null)) {
            if (c == null) return out;
            int idIx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int sizeIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int pathIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
            while (c.moveToNext()) {
                long id = c.getLong(idIx);
                long size = c.isNull(sizeIx) ? 0L : c.getLong(sizeIx);
                String path = c.isNull(pathIx) ? "" : c.getString(pathIx);
                MediaMeta m = new MediaMeta(id, size, path);
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.startsWith("android/media/")) originals.put(id, m);
                else if (lower.startsWith("pictures/ordenafotos/")) copies.put(id, m);
            }
        } catch (Exception e) {
            return out;
        }

        for (AnalysisDb.CleanupVerified v : verified) {
            MediaMeta original = originals.get(v.mediaId);
            MediaMeta copy = copies.get(v.copyId);
            if (original == null || copy == null) continue;
            if (v.bytes <= 0 || original.size != v.bytes || copy.size != v.bytes) continue;
            if (v.sha256 == null || v.sha256.length() != 64) continue;
            out.add(new DeleteItem(v.mediaId,
                    ContentUris.withAppendedId(collection, v.mediaId), v.bytes));
        }
        return out;
    }

    private void beginDeleteBatches(ArrayList<DeleteItem> safe) {
        deleteBatches.clear();
        for (int i = 0; i < safe.size(); i += DELETE_BATCH) {
            deleteBatches.add(new ArrayList<>(safe.subList(i, Math.min(safe.size(), i + DELETE_BATCH))));
        }
        deleting = true;
        deleteBatchIndex = 0;
        deletedCount = 0;
        deletedBytes = 0L;
        requestDeleteBatch();
    }

    private void requestDeleteBatch() {
        if (!deleting) return;
        if (deleteBatchIndex >= deleteBatches.size()) {
            finishDelete(true, "Limpieza terminada");
            return;
        }
        ArrayList<DeleteItem> batch = deleteBatches.get(deleteBatchIndex);
        ArrayList<Uri> uris = new ArrayList<>();
        for (DeleteItem item : batch) uris.add(item.uri);
        status.setText("Solicitando borrado del lote " + (deleteBatchIndex + 1) + " de " + deleteBatches.size() +
                " • eliminados hasta ahora: " + deletedCount);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } else {
                ArrayList<Long> removedIds = new ArrayList<>();
                for (DeleteItem item : batch) {
                    if (getContentResolver().delete(item.uri, null, null) > 0) {
                        removedIds.add(item.mediaId);
                        deletedCount++;
                        deletedBytes += item.bytes;
                    }
                }
                db.removeCleanupVerified(removedIds);
                deleteBatchIndex++;
                requestDeleteBatch();
            }
        } catch (Exception e) {
            finishDelete(false, "Android no pudo preparar el borrado: " + e.getClass().getSimpleName());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DELETE || !deleting || deleteBatchIndex >= deleteBatches.size()) return;
        ArrayList<DeleteItem> batch = deleteBatches.get(deleteBatchIndex);
        if (resultCode == RESULT_OK) {
            ArrayList<Long> ids = new ArrayList<>();
            for (DeleteItem item : batch) {
                ids.add(item.mediaId);
                deletedCount++;
                deletedBytes += item.bytes;
            }
            db.removeCleanupVerified(ids);
            deleteBatchIndex++;
            refreshState();
            requestDeleteBatch();
        } else {
            finishDelete(false, "Borrado cancelado. No se tocaron los lotes restantes.");
        }
    }

    private void finishDelete(boolean complete, String title) {
        deleting = false;
        deleteBatches.clear();
        prefs.edit().putString(CleanupService.K_STATUS,
                complete ? "Limpieza terminada: " + deletedCount + " originales eliminados."
                        : "Limpieza detenida: " + deletedCount + " originales eliminados.").apply();
        refreshState();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Originales eliminados: " + deletedCount +
                        "\nEspacio liberado: " + formatBytes(deletedBytes) +
                        "\nVerificados pendientes: " + db.countCleanupVerified() +
                        "\n\nLas copias de Pictures/OrdenaFotos se conservaron.")
                .setPositiveButton("Perfecto", null)
                .show();
    }

    private void refreshState() {
        boolean running = prefs.getBoolean(CleanupService.K_RUNNING, false);
        boolean complete = prefs.getBoolean(CleanupService.K_COMPLETE, false);
        int scanned = prefs.getInt(CleanupService.K_SCANNED, 0);
        int total = prefs.getInt(CleanupService.K_TOTAL, 0);
        int verified = db == null ? 0 : db.countCleanupVerified();
        long bytes = db == null ? 0L : db.sumCleanupVerifiedBytes();
        int failed = prefs.getInt(CleanupService.K_FAILED, 0);
        String s = prefs.getString(CleanupService.K_STATUS, "Pulsa Verificar duplicados para comenzar.");

        if (total > 0) progress.setProgress(Math.min(1000, (int) ((scanned * 1000L) / total)));
        else progress.setProgress(0);
        status.setText(deleting ? status.getText() : s);

        StringBuilder sb = new StringBuilder();
        sb.append("Revisadas: ").append(scanned).append(" de ").append(total);
        sb.append("\nOriginales con copia SHA-256 idéntica: ").append(verified);
        sb.append("\nEspacio recuperable verificado: ").append(formatBytes(bytes));
        if (failed > 0) sb.append("\nArchivos que no pude leer durante la revisión: ").append(failed);
        if (complete) sb.append("\nVerificación completa ✅");
        result.setText(sb.toString());

        verifyButton.setEnabled(!running && !preparingDelete && !deleting);
        verifyButton.setText(!complete && scanned > 0 ? "REANUDAR VERIFICACIÓN" : "VERIFICAR DUPLICADOS");
        stopButton.setEnabled(running);
        deleteButton.setEnabled(!running && !preparingDelete && !deleting && complete && verified > 0);
        mainButton.setEnabled(!preparingDelete && !deleting);
    }

    private boolean hasPhotoPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPhotoPermission() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_READ);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ) {
            if (hasPhotoPermission()) startVerification();
            else Toast.makeText(this, "Necesito permiso a todas las fotos para comparar originales y copias.", Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2402);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, unit >= 3 ? "%.2f %s" : "%.1f %s", value, units[unit]);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, 1);
        return t;
    }

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static class MediaMeta {
        final long id;
        final long size;
        final String path;
        MediaMeta(long id, long size, String path) {
            this.id = id;
            this.size = size;
            this.path = path == null ? "" : path;
        }
    }

    private static class DeleteItem {
        final long mediaId;
        final Uri uri;
        final long bytes;
        DeleteItem(long mediaId, Uri uri, long bytes) {
            this.mediaId = mediaId;
            this.uri = uri;
            this.bytes = bytes;
        }
    }
}
