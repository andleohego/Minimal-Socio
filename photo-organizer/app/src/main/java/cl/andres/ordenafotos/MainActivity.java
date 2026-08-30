package cl.andres.ordenafotos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_READ = 1001;
    private static final int REQ_WRITE = 1002;
    private static final int WRITE_BATCH = 1900;
    private static final String ROOT_FOLDER = "OrdenaFotos";

    private static final String K_MOVE_REQUESTED = "move_requested";
    private static final String K_MOVE_MOVED = "move_moved";
    private static final String K_MOVE_FAILED = "move_failed";
    private static final String K_MOVE_DENIED = "move_denied";
    private static final String K_BY_DATE = "by_date";
    private static final String K_SYNC_DONE = "sync_v121_done";
    private static final String K_SYNC_PHYSICAL = "sync_v121_physical";
    private static final String K_SYNC_REMOVED = "sync_v121_removed";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<AnalysisDb.ResultRow> previewRows = new ArrayList<>();
    private final ArrayList<ArrayList<AnalysisDb.ResultRow>> moveBatches = new ArrayList<>();
    private final ExecutorService statsExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private AnalysisDb db;
    private ProgressBar progress;
    private TextView status;
    private TextView summary;
    private TextView moveStatus;
    private TextView syncStatus;
    private Button startButton;
    private Button pauseButton;
    private Button stopButton;
    private Button resetButton;
    private Button applyButton;
    private Button syncButton;
    private Button openFolderButton;
    private CheckBox byDate;
    private PreviewAdapter adapter;

    private int currentBatch = -1;
    private int moved = 0;
    private int failed = 0;
    private int denied = 0;
    private int requestedMoves = 0;
    private int physicalOrganized = 0;
    private boolean organizeByDate = false;
    private boolean movingOrder = false;
    private boolean syncBusy = false;
    private volatile boolean physicalCountBusy = false;
    private long lastPhysicalRefresh = 0L;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(AnalysisService.PREFS, MODE_PRIVATE);
        db = new AnalysisDb(this);
        buildUi();
        refreshPhysicalCountAsync();
        refreshState();
        maybeAutoSync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
        refreshPhysicalCountAsync();
        maybeAutoSync();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        statsExecutor.shutdownNow();
        db.close();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setBackgroundColor(0xFFF5F7F8);

        TextView title = text("OrdenaFotos Socio v1.2.1", 25, 0xFF102027, true);
        root.addView(title);
        TextView subtitle = text("Análisis persistente • sincronización segura • ordenado controlado", 14, 0xFF455A64, false);
        subtitle.setPadding(0, dp(3), 0, dp(9));
        root.addView(subtitle);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        status = text("Preparando…", 14, 0xFF263238, true);
        status.setPadding(0, dp(7), 0, dp(3));
        root.addView(status);

        summary = text("", 13, 0xFF00695C, false);
        summary.setPadding(0, 0, 0, dp(6));
        root.addView(summary);

        syncStatus = text("", 13, 0xFF37474F, true);
        syncStatus.setPadding(0, dp(2), 0, dp(5));
        root.addView(syncStatus);

        moveStatus = text("", 13, 0xFF37474F, true);
        moveStatus.setPadding(0, dp(2), 0, dp(7));
        root.addView(moveStatus);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("Iniciar / continuar", v -> requestAndStart(false));
        pauseButton = button("Pausar", v -> togglePause());
        row1.addView(startButton, weight());
        LinearLayout.LayoutParams p2 = weight(); p2.setMarginStart(dp(6));
        row1.addView(pauseButton, p2);
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        stopButton = button("Detener", v -> sendServiceAction(AnalysisService.ACTION_STOP));
        resetButton = button("Desde cero", v -> confirmReset());
        row2.addView(stopButton, weight());
        LinearLayout.LayoutParams p4 = weight(); p4.setMarginStart(dp(6));
        row2.addView(resetButton, p4);
        root.addView(row2);

        byDate = new CheckBox(this);
        byDate.setText("Al ordenar, separar además por año/mes");
        byDate.setTextColor(0xFF263238);
        byDate.setChecked(prefs.getBoolean(K_BY_DATE, false));
        byDate.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(K_BY_DATE, isChecked).apply());
        root.addView(byDate);

        syncButton = button("Sincronizar fotos ya ordenadas", v -> syncOrganizedAsync(true));
        root.addView(syncButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        applyButton = button("Aplicar orden a las fotos seguras", v -> prepareMove());
        root.addView(applyButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        openFolderButton = button("Abrir carpeta OrdenaFotos", v -> openOrderFolder());
        root.addView(openFolderButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = text("Vista previa: últimas 150 analizadas. Las ya movidas quedan desmarcadas.", 12, 0xFF607D8B, false);
        note.setPadding(0, dp(7), 0, dp(4));
        root.addView(note);

        ListView list = new ListView(this);
        adapter = new PreviewAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void requestAndStart(boolean clear) {
        if (movingOrder || syncBusy) {
            Toast.makeText(this, "Termina primero el proceso en curso.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hasFullReadPermission()) {
            startAnalysisService(clear);
            requestNotificationPermissionIfNeeded();
            return;
        }
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
        else permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(permissions.toArray(new String[0]), REQ_READ);
    }

    private boolean hasFullReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ) {
            if (hasFullReadPermission()) {
                startAnalysisService(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Necesito acceso a todas las fotos")
                        .setMessage("Para analizar toda la galería debes elegir “Permitir todas las fotos”. Con acceso limitado Android solo entrega las fotos seleccionadas.")
                        .setPositiveButton("Entendido", null).show();
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1003);
        }
    }

    private void startAnalysisService(boolean clear) {
        Intent i = new Intent(this, AnalysisService.class)
                .setAction(clear ? AnalysisService.ACTION_CLEAR_AND_START : AnalysisService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void togglePause() {
        boolean running = prefs.getBoolean("running", false);
        boolean paused = prefs.getBoolean("paused", false);
        if (!running) {
            requestAndStart(false);
            return;
        }
        sendServiceAction(paused ? AnalysisService.ACTION_RESUME : AnalysisService.ACTION_PAUSE);
    }

    private void sendServiceAction(String action) {
        Intent i = new Intent(this, AnalysisService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && AnalysisService.ACTION_RESUME.equals(action)) startForegroundService(i);
        else startService(i);
    }

    private void confirmReset() {
        if (prefs.getBoolean("running", false) || movingOrder || syncBusy) {
            Toast.makeText(this, "Detén el proceso antes de reiniciarlo desde cero.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("¿Analizar todo desde cero?")
                .setMessage("Borrará solo los resultados internos de OrdenaFotos. No borra ni mueve ninguna foto.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Sí, desde cero", (d, w) -> requestAndStart(true))
                .show();
    }

    private void refreshState() {
        boolean running = prefs.getBoolean("running", false);
        boolean paused = prefs.getBoolean("paused", false);
        int total = prefs.getInt("total", 0);
        int done = prefs.getInt("done", db.count());
        String s = prefs.getString("status", "Pulsa Iniciar para analizar la galería.");

        if (total > 0) progress.setProgress(Math.min(1000, (int) ((done * 1000L) / total)));
        else progress.setProgress(0);
        status.setText(s);

        int selected = db.countSelected();
        Map<String, Integer> counts = db.categoryCounts();
        StringBuilder sb = new StringBuilder();
        sb.append("Guardadas: ").append(db.count()).append("  •  Pendientes seguras: ").append(selected);
        sb.append("\nEn Pictures/OrdenaFotos: ").append(physicalOrganized);
        int shown = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (shown++ >= 6) break;
            sb.append("\n").append(e.getKey()).append(": ").append(e.getValue());
        }
        summary.setText(sb.toString());

        if (syncBusy) {
            syncStatus.setText("Sincronizando con las fotos que ya están físicamente ordenadas…");
        } else if (prefs.getBoolean(K_SYNC_DONE, false)) {
            syncStatus.setText("Sincronización OK: detectadas " + prefs.getInt(K_SYNC_PHYSICAL, 0) +
                    " • quitadas de pendientes " + prefs.getInt(K_SYNC_REMOVED, 0) +
                    " • pendientes ahora " + selected);
        } else {
            syncStatus.setText("Sincronización pendiente: todavía no aplicar el orden.");
        }

        if (!movingOrder) {
            int lr = prefs.getInt(K_MOVE_REQUESTED, 0);
            int lm = prefs.getInt(K_MOVE_MOVED, 0);
            int lf = prefs.getInt(K_MOVE_FAILED, 0);
            int ld = prefs.getInt(K_MOVE_DENIED, 0);
            if (lr > 0) {
                moveStatus.setText("Último orden: movidas " + lm + " • fallidas " + lf +
                        " • no autorizadas " + ld + " • pendientes ahora " + selected);
            } else {
                moveStatus.setText("Carpeta destino: Almacenamiento interno/Pictures/OrdenaFotos");
            }
        }

        startButton.setEnabled((!running || paused) && !movingOrder && !syncBusy);
        pauseButton.setEnabled(running && !movingOrder && !syncBusy);
        pauseButton.setText(paused ? "Continuar" : "Pausar");
        stopButton.setEnabled(running && !movingOrder && !syncBusy);
        resetButton.setEnabled(!running && !movingOrder && !syncBusy);
        syncButton.setEnabled(!running && !movingOrder && !syncBusy && hasFullReadPermission());
        applyButton.setEnabled(!running && selected > 0 && !movingOrder && !syncBusy && prefs.getBoolean(K_SYNC_DONE, false));
        byDate.setEnabled(!movingOrder && !syncBusy);

        if (!running || previewRows.isEmpty() || done % 25 == 0) refreshPreview();

        long now = System.currentTimeMillis();
        if (!running && !syncBusy && now - lastPhysicalRefresh > 5000L) refreshPhysicalCountAsync();
    }

    private void refreshPreview() {
        previewRows.clear();
        previewRows.addAll(db.latest(150));
        adapter.notifyDataSetChanged();
    }

    private void maybeAutoSync() {
        if (!prefs.getBoolean(K_SYNC_DONE, false)
                && hasFullReadPermission()
                && !prefs.getBoolean("running", false)
                && !movingOrder
                && !syncBusy) {
            syncOrganizedAsync(true);
        }
    }

    private void syncOrganizedAsync(boolean showDialog) {
        if (syncBusy || movingOrder || prefs.getBoolean("running", false)) return;
        if (!hasFullReadPermission()) {
            Toast.makeText(this, "Concede permiso a todas las fotos antes de sincronizar.", Toast.LENGTH_LONG).show();
            return;
        }
        if (statsExecutor.isShutdown()) return;

        syncBusy = true;
        refreshState();
        statsExecutor.execute(() -> {
            ArrayList<Long> ids = new ArrayList<>();
            boolean queryOk = false;
            try {
                Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
                String[] args = {Environment.DIRECTORY_PICTURES + "/" + ROOT_FOLDER + "/%"};
                try (Cursor c = getContentResolver().query(collection,
                        new String[]{MediaStore.Images.Media._ID}, selection, args, null)) {
                    if (c != null) {
                        int idIx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                        while (c.moveToNext()) ids.add(c.getLong(idIx));
                        queryOk = true;
                    }
                }
            } catch (Exception ignored) { }

            final boolean ok = queryOk;
            final int physical = ids.size();
            final int removed = ok ? db.markOrganizedIds(ids) : 0;
            final int pending = db.countSelected();

            if (ok) {
                physicalOrganized = physical;
                prefs.edit()
                        .putBoolean(K_SYNC_DONE, true)
                        .putInt(K_SYNC_PHYSICAL, physical)
                        .putInt(K_SYNC_REMOVED, removed)
                        .apply();
            }
            syncBusy = false;

            runOnUiThread(() -> {
                if (isFinishing()) return;
                refreshPreview();
                refreshState();
                if (!ok) {
                    new AlertDialog.Builder(this)
                            .setTitle("No pude sincronizar")
                            .setMessage("No fue posible leer las fotos dentro de Pictures/OrdenaFotos. Revisa el permiso de Fotos y videos.")
                            .setPositiveButton("Entendido", null)
                            .show();
                } else if (showDialog) {
                    new AlertDialog.Builder(this)
                            .setTitle("Sincronización terminada")
                            .setMessage("Fotos detectadas en OrdenaFotos: " + physical +
                                    "\nQuitadas de la cola pendiente: " + removed +
                                    "\nPendientes seguras ahora: " + pending +
                                    "\n\nNo se movió ni borró ninguna foto durante esta sincronización.")
                            .setPositiveButton("Perfecto", null)
                            .show();
                }
            });
        });
    }

    private void prepareMove() {
        if (prefs.getBoolean("running", false)) {
            Toast.makeText(this, "Espera a que termine el análisis antes de mover fotos.", Toast.LENGTH_LONG).show();
            return;
        }
        if (movingOrder || syncBusy) return;
        if (!prefs.getBoolean(K_SYNC_DONE, false)) {
            Toast.makeText(this, "Primero debo sincronizar las fotos que ya fueron ordenadas.", Toast.LENGTH_LONG).show();
            syncOrganizedAsync(true);
            return;
        }

        List<AnalysisDb.ResultRow> selected = db.selectedRows();
        if (selected.isEmpty()) {
            Toast.makeText(this, "No quedan fotos seguras pendientes de ordenar.", Toast.LENGTH_SHORT).show();
            refreshPhysicalCountAsync();
            return;
        }

        organizeByDate = byDate.isChecked();
        moveBatches.clear();
        for (int i = 0; i < selected.size(); i += WRITE_BATCH) {
            moveBatches.add(new ArrayList<>(selected.subList(i, Math.min(selected.size(), i + WRITE_BATCH))));
        }

        moved = 0;
        failed = 0;
        denied = 0;
        requestedMoves = selected.size();
        currentBatch = 0;
        prefs.edit()
                .putInt(K_MOVE_REQUESTED, requestedMoves)
                .putInt(K_MOVE_MOVED, 0)
                .putInt(K_MOVE_FAILED, 0)
                .putInt(K_MOVE_DENIED, 0)
                .apply();

        int prompts = moveBatches.size();
        new AlertDialog.Builder(this)
                .setTitle("Ordenar " + selected.size() + " fotos")
                .setMessage("Se usarán " + prompts + " lotes como máximo. Android pedirá autorización para cada lote.\n\n" +
                        "Destino: Pictures/OrdenaFotos/\n" +
                        (organizeByDate ? "Con subcarpetas por año/mes.\n\n" : "Sin separar por fecha.\n\n") +
                        "Las ya sincronizadas no se incluyen. No se borrará ninguna foto. Si un lote falla o se cancela, queda pendiente para reintentar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Comenzar orden", (d, w) -> {
                    movingOrder = true;
                    requestCurrentWriteBatch();
                })
                .show();
    }

    private void requestCurrentWriteBatch() {
        if (currentBatch < 0 || currentBatch >= moveBatches.size()) {
            finishMoveSession();
            return;
        }

        ArrayList<AnalysisDb.ResultRow> batch = moveBatches.get(currentBatch);
        moveStatus.setText("Ordenando lote " + (currentBatch + 1) + " de " + moveBatches.size() +
                " • " + batch.size() + " fotos\nMovidas: " + moved + " • Fallidas: " + failed +
                " • No autorizadas: " + denied + " • Pendientes: " + db.countSelected());

        if (Build.VERSION.SDK_INT >= 30) {
            Collection<Uri> uris = new ArrayList<>();
            for (AnalysisDb.ResultRow r : batch) uris.add(Uri.parse(r.uri));
            try {
                PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
            } catch (Exception e) {
                failed += batch.size();
                saveMoveCounters();
                Toast.makeText(this, "No pude pedir permiso para el lote " + (currentBatch + 1) + ": " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                currentBatch++;
                requestCurrentWriteBatch();
            }
        } else {
            moveCurrentBatch();
            currentBatch++;
            requestCurrentWriteBatch();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_WRITE) {
            if (currentBatch >= 0 && currentBatch < moveBatches.size()) {
                if (resultCode == RESULT_OK) {
                    moveCurrentBatch();
                } else {
                    denied += moveBatches.get(currentBatch).size();
                    saveMoveCounters();
                    Toast.makeText(this, "Lote " + (currentBatch + 1) + " no autorizado; queda pendiente.", Toast.LENGTH_SHORT).show();
                }
            }
            currentBatch++;
            requestCurrentWriteBatch();
        }
    }

    private void moveCurrentBatch() {
        if (currentBatch < 0 || currentBatch >= moveBatches.size()) return;
        for (AnalysisDb.ResultRow r : moveBatches.get(currentBatch)) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.RELATIVE_PATH, destinationFor(r));
                int n = getContentResolver().update(Uri.parse(r.uri), v, null, null);
                if (n > 0) {
                    moved++;
                    db.setSelected(r.mediaId, false);
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
            }
        }
        saveMoveCounters();
        refreshPhysicalCountAsync();
        refreshPreview();
    }

    private void saveMoveCounters() {
        prefs.edit()
                .putInt(K_MOVE_REQUESTED, requestedMoves)
                .putInt(K_MOVE_MOVED, moved)
                .putInt(K_MOVE_FAILED, failed)
                .putInt(K_MOVE_DENIED, denied)
                .apply();
    }

    private void finishMoveSession() {
        movingOrder = false;
        saveMoveCounters();
        refreshPhysicalCountAsync();
        int pending = db.countSelected();
        refreshPreview();
        refreshState();

        new AlertDialog.Builder(this)
                .setTitle("Ordenado finalizado")
                .setMessage("Movidas: " + moved + " de " + requestedMoves +
                        "\nFallidas: " + failed +
                        "\nNo autorizadas: " + denied +
                        "\nPendientes para reintentar: " + pending +
                        "\n\nCarpeta: Almacenamiento interno/Pictures/OrdenaFotos")
                .setNegativeButton("Cerrar", null)
                .setPositiveButton("Abrir carpeta", (d, w) -> openOrderFolder())
                .show();
    }

    private String destinationFor(AnalysisDb.ResultRow r) {
        String base = Environment.DIRECTORY_PICTURES + "/" + ROOT_FOLDER + "/" + sanitize(r.category) + "/";
        if (!organizeByDate || r.taken <= 0) return base;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(r.taken);
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH) + 1;
        return base + year + "/" + String.format(Locale.ROOT, "%02d", month) + "/";
    }

    private void refreshPhysicalCountAsync() {
        if (physicalCountBusy || statsExecutor.isShutdown()) return;
        physicalCountBusy = true;
        lastPhysicalRefresh = System.currentTimeMillis();
        statsExecutor.execute(() -> {
            int count = 0;
            try {
                Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
                String[] args = {Environment.DIRECTORY_PICTURES + "/" + ROOT_FOLDER + "/%"};
                try (Cursor c = getContentResolver().query(collection,
                        new String[]{MediaStore.Images.Media._ID}, selection, args, null)) {
                    if (c != null) count = c.getCount();
                }
            } catch (Exception ignored) { }
            final int finalCount = count;
            physicalOrganized = finalCount;
            physicalCountBusy = false;
            runOnUiThread(() -> {
                if (!isFinishing()) refreshState();
            });
        });
    }

    private void openOrderFolder() {
        Uri folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:" + Environment.DIRECTORY_PICTURES + "/" + ROOT_FOLDER);
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(view);
        } catch (Exception first) {
            try {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                if (Build.VERSION.SDK_INT >= 26) picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);
                startActivity(picker);
            } catch (Exception second) {
                Toast.makeText(this, "Abre Archivos > Almacenamiento interno > Pictures > OrdenaFotos.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private String sanitize(String s) {
        return s == null ? "Otros" : s.replaceAll("[\\/:*?\"<>|]", "_").trim();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, 1);
        return t;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class PreviewAdapter extends BaseAdapter {
        @Override public int getCount() { return previewRows.size(); }
        @Override public Object getItem(int position) { return previewRows.get(position); }
        @Override public long getItemId(int position) { return previewRows.get(position).mediaId; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AnalysisDb.ResultRow r = previewRows.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(5), dp(4), dp(5));

            CheckBox check = new CheckBox(MainActivity.this);
            check.setChecked(r.selected);
            check.setEnabled(!movingOrder && !syncBusy);
            check.setOnCheckedChangeListener((buttonView, isChecked) -> db.setSelected(r.mediaId, isChecked));
            row.addView(check);

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(r.name.isEmpty() ? "Foto " + r.mediaId : r.name, 13, 0xFF263238, true);
            name.setSingleLine(true);
            TextView cat = text(r.category + "  •  " + Math.round(r.confidence * 100) + "%  •  " + r.reason, 12, 0xFF607D8B, false);
            cat.setSingleLine(true);
            texts.addView(name);
            texts.addView(cat);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return row;
        }
    }
}
