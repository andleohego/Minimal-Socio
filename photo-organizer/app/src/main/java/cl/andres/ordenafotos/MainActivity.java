package cl.andres.ordenafotos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

public class MainActivity extends Activity {
    private static final int REQ_READ = 1001;
    private static final int REQ_WRITE = 1002;
    private static final int WRITE_BATCH = 500;
    private static final String ROOT_FOLDER = "OrdenaFotos";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<AnalysisDb.ResultRow> previewRows = new ArrayList<>();
    private final ArrayList<ArrayList<AnalysisDb.ResultRow>> moveBatches = new ArrayList<>();

    private SharedPreferences prefs;
    private AnalysisDb db;
    private ProgressBar progress;
    private TextView status;
    private TextView summary;
    private Button startButton;
    private Button pauseButton;
    private Button stopButton;
    private Button resetButton;
    private Button applyButton;
    private CheckBox byDate;
    private PreviewAdapter adapter;

    private int currentBatch = -1;
    private int moved = 0;
    private int requestedMoves = 0;
    private boolean organizeByDate = false;

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
        db.close();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setBackgroundColor(0xFFF5F7F8);

        TextView title = text("OrdenaFotos Socio v1.1", 25, 0xFF102027, true);
        root.addView(title);
        TextView subtitle = text("Modo trabajo pesado • guarda cada foto • continúa con pantalla bloqueada", 14, 0xFF455A64, false);
        subtitle.setPadding(0, dp(3), 0, dp(9));
        root.addView(subtitle);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        status = text("Preparando…", 14, 0xFF263238, true);
        status.setPadding(0, dp(7), 0, dp(3));
        root.addView(status);

        summary = text("", 13, 0xFF00695C, false);
        summary.setPadding(0, 0, 0, dp(8));
        root.addView(summary);

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
        root.addView(byDate);

        applyButton = button("Aplicar orden a las fotos seguras", v -> prepareMove());
        root.addView(applyButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = text("Vista previa: últimas 150 analizadas. Desmarca una si no quieres moverla.", 12, 0xFF607D8B, false);
        note.setPadding(0, dp(7), 0, dp(4));
        root.addView(note);

        ListView list = new ListView(this);
        adapter = new PreviewAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void requestAndStart(boolean clear) {
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
                        .setMessage("Para analizar más de 20.000 imágenes debes elegir “Permitir todas las fotos”. Con acceso limitado Android solo entrega las fotos seleccionadas.")
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
        if (Build.VERSION.SDK_INT >= 26 && (AnalysisService.ACTION_RESUME.equals(action))) startForegroundService(i);
        else startService(i);
    }

    private void confirmReset() {
        if (prefs.getBoolean("running", false)) {
            Toast.makeText(this, "Detén el análisis antes de reiniciarlo desde cero.", Toast.LENGTH_LONG).show();
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
        sb.append("Guardadas: ").append(db.count()).append("  •  Seguras para ordenar: ").append(selected);
        int shown = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (shown++ >= 6) break;
            sb.append("\n").append(e.getKey()).append(": ").append(e.getValue());
        }
        summary.setText(sb.toString());

        startButton.setEnabled(!running || paused);
        pauseButton.setEnabled(running);
        pauseButton.setText(paused ? "Continuar" : "Pausar");
        stopButton.setEnabled(running);
        resetButton.setEnabled(!running);
        applyButton.setEnabled(!running && selected > 0);

        if (!running || previewRows.isEmpty() || done % 25 == 0) refreshPreview();
    }

    private void refreshPreview() {
        previewRows.clear();
        previewRows.addAll(db.latest(150));
        adapter.notifyDataSetChanged();
    }

    private void prepareMove() {
        if (prefs.getBoolean("running", false)) {
            Toast.makeText(this, "Detén o espera a que termine el análisis antes de mover fotos.", Toast.LENGTH_LONG).show();
            return;
        }
        List<AnalysisDb.ResultRow> selected = db.selectedRows();
        if (selected.isEmpty()) {
            Toast.makeText(this, "No hay fotos marcadas para ordenar.", Toast.LENGTH_SHORT).show();
            return;
        }
        organizeByDate = byDate.isChecked();
        moveBatches.clear();
        for (int i = 0; i < selected.size(); i += WRITE_BATCH) {
            moveBatches.add(new ArrayList<>(selected.subList(i, Math.min(selected.size(), i + WRITE_BATCH))));
        }
        moved = 0;
        requestedMoves = selected.size();
        currentBatch = 0;
        new AlertDialog.Builder(this)
                .setTitle("Ordenar " + selected.size() + " fotos")
                .setMessage("Android pedirá autorización por lotes. No se borrará ninguna foto. Las dudosas no están seleccionadas.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (d, w) -> requestCurrentWriteBatch())
                .show();
    }

    private void requestCurrentWriteBatch() {
        if (currentBatch < 0 || currentBatch >= moveBatches.size()) {
            Toast.makeText(this, "Orden terminado: " + moved + " de " + requestedMoves + " movidas.", Toast.LENGTH_LONG).show();
            refreshState();
            return;
        }
        ArrayList<AnalysisDb.ResultRow> batch = moveBatches.get(currentBatch);
        if (Build.VERSION.SDK_INT >= 30) {
            Collection<Uri> uris = new ArrayList<>();
            for (AnalysisDb.ResultRow r : batch) uris.add(Uri.parse(r.uri));
            try {
                PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
            } catch (Exception e) {
                Toast.makeText(this, "No pude pedir permiso para mover este lote: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
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
            if (resultCode == RESULT_OK) moveCurrentBatch();
            else Toast.makeText(this, "Lote omitido porque no se autorizó.", Toast.LENGTH_SHORT).show();
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
                }
            } catch (Exception ignored) { }
        }
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
