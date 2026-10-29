package com.gyoche.check;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1, REQ_GALLERY = 2, REQ_VOICE = 3, REQ_NOTIFY = 9;
    private static final String RELEASE_API = "https://api.github.com/repos/dlsdml99-create/gyoche-check/releases/latest";

    private WebView web;
    private Uri photoUri;
    private String mode = "";          // "receipt" | "date" | "barcode" | "photo"
    private long latestVersion = 0;
    private String latestUrl = null;
    private boolean pageReady;
    private boolean openedFromNotify;
    private final List<String> pendingJs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            photoUri = saved.getParcelable("photoUri");
            mode = saved.getString("mode", "");
        }
        openedFromNotify = getIntent() != null && getIntent().getBooleanExtra("fromNotify", false);
        web = new WebView(this);
        web.setBackgroundColor(isNight() ? 0xFF151917 : 0xFFF6F4EF);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                for (String c : pendingJs) web.evaluateJavascript(c, null);
                pendingJs.clear();
                if (openedFromNotify) { openedFromNotify = false; js("window.onOpenFromNotify&&window.onOpenFromNotify()"); }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        Reminder.schedule(this);
        checkUpdate();
        warmUpRecognizers();
        takeShared(getIntent());
    }

    // 다른 앱(카톡 등)에서 "공유 → 챙김이"로 보낸 가족 공유 글을 받음. 글자만 받고, 합칠지는 화면에서 물어봄.
    private void takeShared(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction()) || !"text/plain".equals(i.getType())) return;
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t == null || t.length() > 2000000) return;
        if (!t.contains("GYOFAM1:")) { Toast.makeText(this, "챙김이 가족 공유 내용이 아니에요", Toast.LENGTH_SHORT).show(); return; }
        js("window.onSharedText&&window.onSharedText(" + JSONObject.quote(t) + ")");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("fromNotify", false)) js("window.onOpenFromNotify&&window.onOpenFromNotify()");
        takeShared(intent);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBackKey?String(window.onBackKey()):'false'", v -> {
            if (v == null || !v.contains("true")) finish();
        });
    }

    // ── 웹 화면에서 부르는 기능 ──
    class Bridge {
        @JavascriptInterface
        public void sync(String json) {
            Reminder.save(MainActivity.this, json);
            Reminder.schedule(MainActivity.this);
            ChaenggimWidget.updateAll(MainActivity.this);
        }

        @JavascriptInterface
        public void askNotify() {
            runOnUiThread(MainActivity.this::requestNotifyPermission);
        }

        @JavascriptInterface
        public void testNotify() {
            runOnUiThread(() -> {
                if (!Reminder.canNotify(MainActivity.this)) { askNotifySettings(); return; }
                Reminder.notifyNow(MainActivity.this, true);
                Toast.makeText(MainActivity.this, "알림을 보냈어요. 위에서 아래로 내려 확인해 보세요.", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("챙김이", text));
        }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
        }

        @JavascriptInterface
        public void scanBarcode() {
            runOnUiThread(MainActivity.this::startScanner);
        }

        @JavascriptInterface
        public boolean isNight() {
            return MainActivity.this.isNight();
        }

        @JavascriptInterface
        public void pickPhoto() {
            runOnUiThread(() -> chooseSource("photo"));
        }

        @JavascriptInterface
        public void openUpdate() {
            runOnUiThread(() -> {
                if (latestUrl != null && latestVersion > currentVersion()) showUpdate(latestVersion, latestUrl);
                else Toast.makeText(MainActivity.this, "지금 최신 버전이에요", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public String takeActions() {
            return Reminder.takeActions(MainActivity.this);
        }

        @JavascriptInterface
        public void listen() {
            runOnUiThread(MainActivity.this::startVoice);
        }

        @JavascriptInterface
        public void shareText(String text) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "가족에게 보내기"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "보낼 앱을 찾지 못했어요", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void shareImage(String dataUrl, String text) {
            runOnUiThread(() -> shareImg(dataUrl, text));
        }

        @JavascriptInterface
        public void readText(String kind) {
            runOnUiThread(() -> chooseSource("date".equals(kind) ? "date" : "receipt"));
        }
    }

    // ── 알림 권한 ──
    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        }
    }

    private void askNotifySettings() {
        new AlertDialog.Builder(this)
            .setTitle("알림이 꺼져 있어요")
            .setMessage("교체할 때를 알려드리려면 알림을 허용해 주세요.")
            .setPositiveButton("설정 열기", (d, w) -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                }
            })
            .setNegativeButton("나중에", null)
            .show();
    }

    // ── 바코드 ──
    private static final int[] FORMATS = {Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE};

    private void startScanner() {
        try {
            GmsBarcodeScannerOptions o = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, FORMATS)
                .build();
            GmsBarcodeScanner sc = GmsBarcodeScanning.getClient(this, o);
            sc.startScan()
                .addOnSuccessListener(b -> onCode(b.getRawValue()))
                .addOnCanceledListener(() -> result(fail("barcode", null)))
                .addOnFailureListener(e -> askPhotoBarcode());
        } catch (Throwable t) {
            askPhotoBarcode();
        }
    }

    private void askPhotoBarcode() {
        new AlertDialog.Builder(this)
            .setTitle("바코드 사진 찍기")
            .setMessage("바로 읽는 화면을 열 수 없어요. 바코드를 사진으로 찍어서 읽을게요.")
            .setPositiveButton("사진 찍기", (d, w) -> { mode = "barcode"; openCamera(); })
            .setNegativeButton("취소", (d, w) -> result(fail("barcode", null)))
            .setOnCancelListener(d -> result(fail("barcode", null)))
            .show();
    }

    private void decodeBarcodePhoto(Uri u) {
        status("바코드 읽는 중…");
        new Thread(() -> {
            Bitmap bmp;
            try { bmp = loadUpright(u); } catch (Exception e) { result(fail("barcode", "사진을 열 수 없어요.")); return; }
            BarcodeScanner sc = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, FORMATS).build());
            sc.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(list -> {
                    String code = null;
                    for (Barcode b : list) { if (b.getRawValue() != null) { code = b.getRawValue(); break; } }
                    if (code == null) result(fail("barcode", "바코드를 찾지 못했어요. 바코드가 화면 가운데 크게 나오게 찍어주세요."));
                    else onCode(code);
                })
                .addOnFailureListener(e -> result(fail("barcode", downloading(e) ? "바코드 인식 기능을 처음 한 번 내려받는 중이에요. 1~2분 뒤 다시 해주세요." : "바코드를 읽지 못했어요.")));
        }).start();
    }

    // 바코드 숫자로 상품 이름 찾기 (Open Food Facts 공개 자료, 못 찾으면 이름 없이)
    private void onCode(String code) {
        if (code == null) { result(fail("barcode", "바코드를 읽지 못했어요.")); return; }
        status("상품 찾는 중…");
        new Thread(() -> {
            String name = null;
            if (code.matches("\\d{8,14}")) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://world.openfoodfacts.org/api/v2/product/" + code + ".json?fields=product_name,product_name_ko,brands").openConnection();
                    c.setRequestProperty("User-Agent", "GyocheCheck/1.0 (Android)");
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    if (c.getResponseCode() == 200) {
                        JSONObject o = new JSONObject(readAll(c.getInputStream()));
                        JSONObject p = o.optJSONObject("product");
                        if (o.optInt("status") == 1 && p != null) {
                            String n = p.optString("product_name_ko", "").trim();
                            if (n.isEmpty()) n = p.optString("product_name", "").trim();
                            String brand = p.optString("brands", "").split(",")[0].trim();
                            if (!n.isEmpty()) name = (!brand.isEmpty() && !n.contains(brand)) ? brand + " " + n : n;
                        }
                    }
                } catch (Exception ignored) {}
            }
            try {
                JSONObject r = new JSONObject();
                r.put("type", "barcode");
                r.put("ok", true);
                r.put("code", code);
                if (name != null) r.put("name", name);
                result(r);
            } catch (Exception e) {
                result(fail("barcode", "결과를 처리하지 못했어요."));
            }
        }).start();
    }

    // ── 사진에서 글자 읽기 (영수증 / 유통기한) ──
    private void chooseSource(String kind) {
        mode = kind;
        String title = "date".equals(kind) ? "유통기한 날짜 찍기" : "photo".equals(kind) ? "물건 사진 넣기" : "영수증 찍기";
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(new String[]{"사진 찍기", "앨범에서 고르기"}, (d, which) -> { if (which == 0) openCamera(); else openGallery(); })
            .setOnCancelListener(d -> result(fail(kind, null)))
            .show();
    }

    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "shots");
            dir.mkdirs();
            File f = new File(dir, "shot.jpg");
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            result(fail(mode, "카메라를 열 수 없어요. 앨범에서 골라주세요."));
        }
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(Intent.createChooser(i, "사진 고르기"), REQ_GALLERY);
        } catch (Exception e) {
            result(fail(mode, "앨범을 열 수 없어요."));
        }
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    // ── 말로 등록: 폰의 음성 인식 화면을 빌려 씀 (이 앱은 마이크 권한이 없음) ──
    private void startVoice() {
        try {
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
            i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "예: 우유 두 개 이마트 오천 원");
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            voiceResult(null, "이 폰에서는 음성 인식을 쓸 수 없어요. Google 앱이 설치돼 있는지 확인해 주세요.");
        }
    }

    private void voiceResult(String text, String err) {
        try {
            JSONObject o = new JSONObject().put("type", "voice").put("ok", text != null);
            if (text != null) o.put("text", text);
            if (err != null) o.put("error", err);
            result(o);
        } catch (Exception ignored) {}
    }

    // ── 결산 카드 공유: 앱 임시 폴더에 그림을 저장해 카톡 등으로 보냄 ──
    private void shareImg(String dataUrl, String text) {
        try {
            String b = dataUrl.substring(dataUrl.indexOf(',') + 1);
            byte[] png = Base64.decode(b, Base64.DEFAULT);
            File dir = new File(getCacheDir(), "shots");
            dir.mkdirs();
            File f = new File(dir, "chaenggim_card.png");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(png);
            out.close();
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, u);
            if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "결산 카드 보내기"));
        } catch (Exception e) {
            Toast.makeText(this, "그림을 보내지 못했어요", Toast.LENGTH_SHORT).show();
        }
    }

    // 글자·바코드 인식 기능을 미리 내려받아 두기 (처음 한 번, 뒤에서 조용히)
    private void warmUpRecognizers() {
        android.content.SharedPreferences p = getSharedPreferences(Reminder.PREF, MODE_PRIVATE);
        if (p.getBoolean("mlReady", false)) return;
        new Thread(() -> {
            try {
                Bitmap tiny = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                InputImage img = InputImage.fromBitmap(tiny, 0);
                TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()).process(img)
                    .addOnSuccessListener(t -> p.edit().putBoolean("mlReady", true).apply());
                BarcodeScanning.getClient().process(img);
            } catch (Throwable ignored) {}
        }).start();
    }

    private static boolean downloading(Exception e) {
        String m = String.valueOf(e == null ? "" : e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("download") || m.contains("waiting") || m.contains("unavailable");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE) {
            java.util.ArrayList<String> r = res == RESULT_OK && data != null ? data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS) : null;
            voiceResult(r != null && !r.isEmpty() ? r.get(0) : null, null);
            return;
        }
        if (req != REQ_CAMERA && req != REQ_GALLERY) return;
        if (res != RESULT_OK) { result(fail(mode, null)); return; }
        Uri u = req == REQ_CAMERA ? photoUri : (data != null ? data.getData() : null);
        if (u == null) { result(fail(mode, "사진을 가져오지 못했어요.")); return; }
        if ("barcode".equals(mode)) decodeBarcodePhoto(u);
        else if ("photo".equals(mode)) makePhoto(u);
        else recognize(u, mode);
    }

    // 물건 사진: 폰 안에서 줄여서(큰 사진 1080px, 목록용 200px) 앱 화면에 넘긴다. 밖으로 보내지 않음.
    private void makePhoto(Uri u) {
        status("사진 줄이는 중…");
        new Thread(() -> {
            try {
                Bitmap bmp = loadUpright(u);
                JSONObject r = new JSONObject();
                r.put("type", "photo");
                r.put("kind", "photo");
                r.put("ok", true);
                r.put("full", jpeg(bmp, 1080, 78));
                r.put("thumb", jpeg(bmp, 200, 75));
                result(r);
            } catch (Exception e) {
                result(fail("photo", "사진을 열 수 없어요."));
            }
        }).start();
    }

    private static String jpeg(Bitmap src, int max, int q) {
        int w = src.getWidth(), h = src.getHeight();
        float k = Math.min(1f, max / (float) Math.max(w, h));
        Bitmap b = k < 1f ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true) : src;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, q, out);
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private void recognize(Uri u, String kind) {
        status("date".equals(kind) ? "날짜 읽는 중…" : "영수증 읽는 중…");
        new Thread(() -> {
            Bitmap bmp;
            try { bmp = loadUpright(u); } catch (Exception e) { result(fail(kind, "사진을 열 수 없어요.")); return; }
            TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build())
                .process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(t -> {
                    try {
                        JSONObject r = new JSONObject();
                        r.put("type", "ocr");
                        r.put("kind", kind);
                        r.put("ok", true);
                        JSONArray a = new JSONArray();
                        for (String row : rows(t)) a.put(row);
                        r.put("lines", a);
                        result(r);
                    } catch (Exception e) {
                        result(fail(kind, "결과를 처리하지 못했어요."));
                    }
                })
                .addOnFailureListener(e -> result(fail(kind, downloading(e) ? "글자 인식 기능을 처음 한 번 내려받는 중이에요. 1~2분 뒤 다시 찍어주세요. (와이파이 권장)" : "글자를 읽지 못했어요. 밝은 곳에서 다시 찍어주세요.")));
        }).start();
    }

    // 같은 높이에 있는 글자 줄을 한 줄로 합친다 (영수증의 이름·금액 칸이 따로 읽혀도 한 줄로)
    private static class Ln {
        final String text; final int left, top, bottom;
        Ln(String t, Rect r) { text = t; left = r.left; top = r.top; bottom = r.bottom; }
        float cy() { return (top + bottom) / 2f; }
        int h() { return Math.max(1, bottom - top); }
    }

    private static List<String> rows(Text text) {
        List<Ln> ls = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Rect r = l.getBoundingBox();
                ls.add(new Ln(l.getText(), r == null ? new Rect(0, ls.size() * 20, 0, ls.size() * 20 + 10) : r));
            }
        }
        Collections.sort(ls, (a, b) -> Float.compare(a.cy(), b.cy()));
        List<List<Ln>> groups = new ArrayList<>();
        for (Ln l : ls) {
            List<Ln> g = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (g != null) {
                Ln f = g.get(0);
                float tol = Math.min(f.h(), l.h()) * 0.55f;
                if (Math.abs(f.cy() - l.cy()) <= tol) { g.add(l); continue; }
            }
            List<Ln> n = new ArrayList<>();
            n.add(l);
            groups.add(n);
        }
        List<String> out = new ArrayList<>();
        for (List<Ln> g : groups) {
            Collections.sort(g, (a, b) -> Integer.compare(a.left, b.left));
            StringBuilder sb = new StringBuilder();
            for (Ln l : g) { if (sb.length() > 0) sb.append("  "); sb.append(l.text); }
            out.add(sb.toString());
        }
        return out;
    }

    // 큰 사진은 줄이고, 휴대폰 방향대로 똑바로 세운다
    private Bitmap loadUpright(Uri u) throws Exception {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, b); }
        int sample = 1;
        while (Math.max(b.outWidth, b.outHeight) / (sample * 2) >= 1800) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(u)) { bmp = BitmapFactory.decodeStream(in, null, o); }
        if (bmp == null) throw new Exception("decode");
        int rot = 0;
        try (InputStream in = getContentResolver().openInputStream(u)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) {}
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }
        return bmp;
    }

    // ── 새 버전 확인 (GitHub 최신 릴리스와 비교) ──
    private void checkUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                JSONObject o = new JSONObject(readAll(c.getInputStream()));
                String digits = o.optString("tag_name", "").replaceAll("[^0-9]", "");
                if (digits.isEmpty()) return;
                long latest = Long.parseLong(digits);
                String url = null;
                JSONArray assets = o.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
                    }
                }
                latestVersion = latest;
                latestUrl = url;
                js("window.onLatest&&window.onLatest(" + latest + "," + currentVersion() + ")");
                if (url == null || latest <= currentVersion()) return;
                final String u = url;
                runOnUiThread(() -> showUpdate(latest, u));
            } catch (Exception ignored) {}
        }).start();
    }

    @SuppressWarnings("deprecation")
    private long currentVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private void showUpdate(long latest, String url) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle("새 버전이 있어요")
            .setMessage("v1." + latest + " 업데이트가 나왔어요.\n'업데이트'를 누르면 파일이 받아져요. 다 받으면 파일을 눌러 설치하세요. 등록한 물건은 그대로 남아요.")
            .setPositiveButton("업데이트", (d, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            })
            .setNegativeButton("나중에", null)
            .show();
    }

    // ── 공통 ──
    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static JSONObject fail(String kind, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "barcode".equals(kind) ? "barcode" : "photo".equals(kind) ? "photo" : "ocr");
            o.put("kind", kind);
            o.put("ok", false);
            if (msg != null) o.put("error", msg);
        } catch (Exception ignored) {}
        return o;
    }

    private void result(JSONObject o) {
        js("window.onNative&&window.onNative(" + o + ")");
    }

    private void status(String msg) {
        js("window.onNativeStatus&&window.onNativeStatus(" + JSONObject.quote(msg) + ")");
    }

    private void js(String code) {
        web.post(() -> {
            if (pageReady) web.evaluateJavascript(code, null);
            else pendingJs.add(code);
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (photoUri != null) out.putParcelable("photoUri", photoUri);
        out.putString("mode", mode);
    }
}
