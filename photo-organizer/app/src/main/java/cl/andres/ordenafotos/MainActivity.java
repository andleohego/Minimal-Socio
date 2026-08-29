package cl.andres.ordenafotos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_READ = 1001;
    private static final int REQ_WRITE = 1002;
    private static final int WRITE_BATCH = 1000;
    private static final String ROOT_FOLDER = "OrdenaFotos";

    private static final String[] CATEGORIES = {
            "Autos", "Mascotas", "Personas", "Documentos", "Trabajo",
            "Capturas", "WhatsApp", "Comida", "Paisajes", "Otros", "Sin clasificar"
    };

    private final ArrayList<PhotoItem> photos = new ArrayList<>();
    private final ArrayList<ArrayList<PhotoItem>> moveBatches = new ArrayList<>();
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);

    private ImageLabeler imageLabeler;
    private TextRecognizer textRecognizer;
    private PhotoAdapter adapter;
    private TextView status;
    private TextView summary;
    private ProgressBar progress;
    private Button analyzeButton;
    private Button applyButton;
    private CheckBox byDate;
    private int currentBatchIndex = -1;
    private int moveSucceeded = 0;
    private int moveRequested = 0;
    private boolean organizeByDate = false;
    private boolean analyzing = false;

    private LruCache<String, Bitmap> thumbCache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        imageLabeler = ImageLabeling.getClient(
                new ImageLabelerOptions.Builder().setConfidenceThreshold(0.50f).build());
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        int cacheKb = Math.min(32768, (int) (Runtime.getRuntime().maxMemory() / 1024 / 8));
        thumbCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setBackgroundColor(0xFFF5F7F8);

        TextView title = new TextView(this);
        title.setText("OrdenaFotos Socio");
        title.setTextSize(25);
        title.setTextColor(0xFF102027);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Analiza en tu teléfono. Nada se sube. Primero propone; tú decides qué mover.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF455A64);
        subtitle.setPadding(0, dp(3), 0, dp(10));
        root.addView(subtitle);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);

        analyzeButton = new Button(this);
        analyzeButton.setText("Analizar galería");
        analyzeButton.setOnClickListener(v -> requestAndAnalyze());
        buttons.addView(analyzeButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        applyButton = new Button(this);
        applyButton.setText("Aplicar orden");
        applyButton.setEnabled(false);
        applyButton.setOnClickListener(v -> prepareMove());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ap.setMarginStart(dp(8));
        buttons.addView(applyButton, ap);
        root.addView(buttons);

        byDate = new CheckBox(this);
        byDate.setText("Separar además por año/mes");
        byDate.setTextColor(0xFF263238);
        root.addView(byDate);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        status = new TextView(this);
        status.setText("Pulsa “Analizar galería” para comenzar.");
        status.setTextSize(13);
        status.setTextColor(0xFF37474F);
        status.setPadding(0, dp(7), 0, dp(3));
        root.addView(status);

        summary = new TextView(this);
        summary.setText("");
        summary.setTextSize(13);
        summary.setTextColor(0xFF00695C);
        summary.setTypeface(null, 1);
        summary.setPadding(0, 0, 0, dp(6));
        root.addView(summary);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        adapter = new PhotoAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void requestAndAnalyze() {
        if (analyzing) return;
        if (hasReadPermission()) {
            startAnalysis();
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, REQ_READ);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_READ);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
        }
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ) {
            if (hasReadPermission()) startAnalysis();
            else Toast.makeText(this, "Necesito permiso para ver las fotos que quieras organizar.", Toast.LENGTH_LONG).show();
        }
    }

    private void startAnalysis() {
        analyzing = true;
        analyzeButton.setEnabled(false);
        applyButton.setEnabled(false);
        photos.clear();
        adapter.notifyDataSetChanged();
        summary.setText("");
        progress.setProgress(0);
        status.setText("Buscando fotos…");

        analysisExecutor.execute(() -> {
            List<MediaRef> refs = loadGallery();
            if (refs.isEmpty()) {
                runOnUiThread(() -> finishAnalysis(new ArrayList<>(), "No encontré fotos accesibles."));
                return;
            }

            ArrayList<PhotoItem> result = new ArrayList<>(refs.size());
            int total = refs.size();
            for (int i = 0; i < total; i++) {
                MediaRef ref = refs.get(i);
                PhotoItem item = analyzePhoto(ref);
                result.add(item);
                int done = i + 1;
                if (done == 1 || done % 3 == 0 || done == total) {
                    int pct = Math.max(1, (done * 100) / total);
                    runOnUiThread(() -> {
                        progress.setProgress(pct);
                        status.setText("Analizando " + done + " de " + total + "…");
                    });
                }
            }
            runOnUiThread(() -> finishAnalysis(result, "Análisis terminado. Revisa las propuestas antes de mover."));
        });
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
                refs.add(new MediaRef(ContentUris.withAppendedId(collection, id), name, path, taken));
            }
        } catch (Exception e) {
            runOnUiThread(() -> status.setText("No pude leer la galería: " + e.getClass().getSimpleName()));
        }
        return refs;
    }

    private PhotoItem analyzePhoto(MediaRef ref) {
        String combined = normalize(ref.path + " " + ref.name);
        boolean screenshot = containsAny(combined, "screenshot", "screenshots", "captura de pantalla");
        boolean whatsapp = containsAny(combined, "whatsapp", "whatsapp images", "wa0", "-wa");

        if (containsAny(combined, "pedidosya", "pedidos ya", "peya", "zubale", "uber", "rappi", "delivery", "reparto", "despacho")) {
            return new PhotoItem(ref, "Trabajo", 1f, true, "regla por nombre/carpeta");
        }
        if (containsAny(combined, "boleta", "factura", "receipt", "invoice", "comprobante", "documento", "scan", "scanner")) {
            return new PhotoItem(ref, "Documentos", 1f, true, "regla por nombre/carpeta");
        }

        try {
            int side = screenshot ? 1080 : 640;
            Bitmap bitmap = getContentResolver().loadThumbnail(ref.uri, new Size(side, side), null);
            InputImage input = InputImage.fromBitmap(bitmap, 0);

            if (screenshot) {
                try {
                    Text text = Tasks.await(textRecognizer.process(input));
                    String ocr = normalize(text == null ? "" : text.getText());
                    if (isWorkText(ocr)) return new PhotoItem(ref, "Trabajo", 0.99f, true, "OCR de captura");
                    if (isDocumentText(ocr)) return new PhotoItem(ref, "Documentos", 0.94f, true, "OCR de captura");
                } catch (Exception ignored) { }
                return new PhotoItem(ref, "Capturas", 0.92f, true, "carpeta de capturas");
            }

            List<ImageLabel> labels = Tasks.await(imageLabeler.process(input));
            Decision decision = decideFromLabels(labels);
            if (decision != null) {
                boolean selected = decision.score >= 0.72f;
                return new PhotoItem(ref, decision.category, decision.score, selected, "IA local");
            }

            if (whatsapp) return new PhotoItem(ref, "WhatsApp", 0.82f, true, "origen WhatsApp");
            return new PhotoItem(ref, "Sin clasificar", 0f, false, "sin coincidencia segura");
        } catch (Exception e) {
            if (whatsapp) return new PhotoItem(ref, "WhatsApp", 0.70f, false, "origen WhatsApp; IA no disponible");
            return new PhotoItem(ref, "Sin clasificar", 0f, false, "no se pudo analizar");
        }
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

    private void addIf(Map<String, Float> scores, String category, float value, String label, String... words) {
        if (!containsAny(label, words)) return;
        Float old = scores.get(category);
        if (old == null || value > old) scores.put(category, value);
    }

    private boolean isWorkText(String t) {
        return containsAny(t,
                "pedidosya", "pedidos ya", "peya rider", "zubale", "uber driver", "uber eats", "rappi",
                "lider", "lider.cl", "pedido", "despacho", "ruta", "reparto", "entrega", "ganancia", "bloque", "turno");
    }

    private boolean isDocumentText(String t) {
        return containsAny(t,
                "boleta", "factura", "comprobante", "transferencia", "banco", "total a pagar", "monto", "rut", "recibo", "pago exitoso");
    }

    private void finishAnalysis(List<PhotoItem> result, String message) {
        photos.clear();
        photos.addAll(result);
        adapter.notifyDataSetChanged();
        progress.setProgress(result.isEmpty() ? 0 : 100);
        status.setText(message);
        analyzing = false;
        analyzeButton.setEnabled(true);
        updateSummary();
    }

    private void updateSummary() {
        int selected = 0;
        int uncertain = 0;
        for (PhotoItem p : photos) {
            if (p.checked && !"Sin clasificar".equals(p.category)) selected++;
            if ("Sin clasificar".equals(p.category)) uncertain++;
        }
        summary.setText(photos.size() + " fotos analizadas · " + selected + " listas para mover · " + uncertain + " sin clasificar");
        applyButton.setEnabled(selected > 0 && !analyzing);
    }

    private void prepareMove() {
        ArrayList<PhotoItem> selected = new ArrayList<>();
        for (PhotoItem p : photos) {
            if (p.checked && !"Sin clasificar".equals(p.category)) selected.add(p);
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "No hay fotos marcadas para mover.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Aplicar organización")
                .setMessage("Se moverán " + selected.size() + " fotos a Pictures/" + ROOT_FOLDER + "/. No se borrará ninguna foto. Android te pedirá confirmar el acceso de escritura.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (d, w) -> beginWriteRequests(selected))
                .show();
    }

    private void beginWriteRequests(ArrayList<PhotoItem> selected) {
        applyButton.setEnabled(false);
        moveBatches.clear();
        currentBatchIndex = -1;
        moveSucceeded = 0;
        moveRequested = selected.size();
        organizeByDate = byDate.isChecked();
        for (int i = 0; i < selected.size(); i += WRITE_BATCH) {
            int end = Math.min(selected.size(), i + WRITE_BATCH);
            moveBatches.add(new ArrayList<>(selected.subList(i, end)));
        }

        if (Build.VERSION.SDK_INT >= 30) {
            requestNextWriteBatch();
        } else {
            int moved = performMove(selected);
            moveSucceeded = moved;
            onMoveFinished(moveSucceeded, moveRequested);
        }
    }

    private void requestNextWriteBatch() {
        currentBatchIndex++;
        if (currentBatchIndex >= moveBatches.size()) {
            onMoveFinished(moveSucceeded, moveRequested);
            return;
        }

        ArrayList<PhotoItem> batch = moveBatches.get(currentBatchIndex);
        ArrayList<Uri> uris = new ArrayList<>(batch.size());
        for (PhotoItem p : batch) uris.add(p.ref.uri);
        try {
            PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), uris);
            status.setText("Esperando autorización de Android para lote " + (currentBatchIndex + 1) + " de " + moveBatches.size() + "…");
            startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | IllegalArgumentException e) {
            status.setText("No pude solicitar permiso de escritura: " + e.getClass().getSimpleName());
            applyButton.setEnabled(true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WRITE) return;
        if (resultCode != RESULT_OK) {
            status.setText("Organización cancelada. No se movió este lote.");
            applyButton.setEnabled(true);
            return;
        }

        ArrayList<PhotoItem> batch = moveBatches.get(currentBatchIndex);
        analysisExecutor.execute(() -> {
            int moved = performMove(batch);
            moveSucceeded += moved;
            runOnUiThread(() -> {
                status.setText("Lote " + (currentBatchIndex + 1) + ": " + moved + " de " + batch.size() + " movidas.");
                requestNextWriteBatch();
            });
        });
    }

    private int performMove(Collection<PhotoItem> items) {
        ContentResolver resolver = getContentResolver();
        int moved = 0;
        for (PhotoItem p : items) {
            try {
                String dest = Environment.DIRECTORY_PICTURES + "/" + ROOT_FOLDER + "/" + p.category + "/";
                if (organizeByDate) dest += datePath(p.ref.dateTaken);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.RELATIVE_PATH, dest);
                int changed = resolver.update(p.ref.uri, values, null, null);
                if (changed > 0) moved++;
            } catch (Exception ignored) { }
        }
        return moved;
    }

    private void onMoveFinished(int moved, int requested) {
        status.setText("Listo: " + moved + " de " + requested + " fotos movidas. Vuelve a analizar para comprobar las carpetas.");
        Toast.makeText(this, moved + " fotos movidas.", Toast.LENGTH_LONG).show();
        applyButton.setEnabled(true);
    }

    private String datePath(long millis) {
        Calendar c = Calendar.getInstance();
        if (millis > 0) c.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d/%02d/", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
    }

    private void chooseCategory(PhotoItem item) {
        int checked = 0;
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(item.category)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("Mover a…")
                .setSingleChoiceItems(CATEGORIES, checked, (dialog, which) -> {
                    item.category = CATEGORIES[which];
                    item.checked = !"Sin clasificar".equals(item.category);
                    item.reason = "selección manual";
                    adapter.notifyDataSetChanged();
                    updateSummary();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int position) { return photos.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(6), dp(4), dp(6));
                row.setBackgroundColor(0xFFFFFFFF);

                CheckBox check = new CheckBox(MainActivity.this);
                row.addView(check, new LinearLayout.LayoutParams(dp(44), dp(44)));

                ImageView image = new ImageView(MainActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageResource(android.R.drawable.ic_menu_gallery);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(72), dp(72));
                ip.setMarginEnd(dp(10));
                row.addView(image, ip);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(MainActivity.this);
                name.setTextColor(0xFF263238);
                name.setTextSize(14);
                name.setMaxLines(1);
                name.setTypeface(null, 1);
                texts.addView(name);

                TextView category = new TextView(MainActivity.this);
                category.setTextColor(0xFF00695C);
                category.setTextSize(14);
                category.setPadding(0, dp(3), 0, dp(2));
                category.setClickable(true);
                texts.addView(category);

                TextView detail = new TextView(MainActivity.this);
                detail.setTextColor(0xFF607D8B);
                detail.setTextSize(11);
                detail.setMaxLines(2);
                texts.addView(detail);

                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                h = new Holder(check, image, name, category, detail);
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }

            PhotoItem item = photos.get(position);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(item.checked);
            h.check.setOnCheckedChangeListener((b, value) -> {
                item.checked = value;
                updateSummary();
            });
            h.name.setText(item.ref.name.isEmpty() ? "Foto" : item.ref.name);
            h.category.setText("Carpeta propuesta: " + item.category + "  ▾");
            h.category.setOnClickListener(v -> chooseCategory(item));
            String conf = item.confidence > 0 ? Math.round(item.confidence * 100) + "%" : "—";
            h.detail.setText(item.reason + " · confianza " + conf + "\n" + item.ref.path);
            loadRowThumbnail(h.image, item.ref.uri);
            return convertView;
        }
    }

    private void loadRowThumbnail(ImageView image, Uri uri) {
        String key = uri.toString();
        image.setTag(key);
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        thumbExecutor.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(uri, new Size(180, 180), null);
                if (b != null) thumbCache.put(key, b);
                runOnUiThread(() -> {
                    if (key.equals(image.getTag()) && b != null) image.setImageBitmap(b);
                });
            } catch (Exception ignored) { }
        });
    }

    private static class Holder {
        CheckBox check; ImageView image; TextView name; TextView category; TextView detail;
        Holder(CheckBox c, ImageView i, TextView n, TextView ca, TextView d) {
            check = c; image = i; name = n; category = ca; detail = d;
        }
    }

    private static class MediaRef {
        Uri uri; String name; String path; long dateTaken;
        MediaRef(Uri uri, String name, String path, long dateTaken) {
            this.uri = uri; this.name = name; this.path = path; this.dateTaken = dateTaken;
        }
    }

    private static class PhotoItem {
        MediaRef ref; String category; float confidence; boolean checked; String reason;
        PhotoItem(MediaRef ref, String category, float confidence, boolean checked, String reason) {
            this.ref = ref; this.category = category; this.confidence = confidence; this.checked = checked; this.reason = reason;
        }
    }

    private static class Decision {
        String category; float score;
        Decision(String category, float score) { this.category = category; this.score = score; }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String normalize(String s) {
        String lower = safe(s).toLowerCase(Locale.ROOT);
        String n = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "");
    }

    private static boolean containsAny(String text, String... words) {
        if (text == null) return false;
        for (String w : words) if (text.contains(normalize(w))) return true;
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (imageLabeler != null) imageLabeler.close();
        if (textRecognizer != null) textRecognizer.close();
        analysisExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
    }
}
