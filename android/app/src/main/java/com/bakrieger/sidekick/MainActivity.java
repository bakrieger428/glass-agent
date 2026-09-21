package com.bakrieger.sidekick;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sidekick v0.2.0 — proactive PaperChat.
 * AUTO: glasses glance every 5s; when motion settles into stillness (you finished
 * writing and are holding the paper up) it captures and answers WITHOUT a tap.
 * TAP = manual capture. DPAD_LEFT = toggle auto. Swipe/volume = scroll.
 * Keys page: http://<glasses-ip>:8080 (photo viewer + test button).
 */
public class MainActivity extends Activity {

    private static final int GREEN = Color.parseColor("#00FF46");
    private static final int GREEN_MID = Color.parseColor("#00A62E");
    private static final int GREEN_DIM = Color.parseColor("#006619");

    // auto-detect tuning
    private static final long SCAN_MS = 5000;        // glance interval
    private static final float MOTION_DIFF = 20f;    // clear movement (real data: writing = 20-40)
    private static final float STILL_DIFF = 16f;     // holding still (real noise band: 6-15)
    private static final int NEED_MOTION = 2;        // consecutive motion scans
    private static final int NEED_STILL = 3;         // ~15s of stillness before auto-fire
    private static final long AUTO_COOLDOWN_MS = 20000;

    private TextView statusView;
    private TextView questionView;
    private TextView answerView;
    private ScrollView scroller;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean busy = false;
    private long lastTap = 0;

    // auto loop state
    private final Handler auto = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::autoScan;
    private boolean autoOn = true;
    private float[] prevFrame = null;
    private int motionRun = 0;
    private int stillRun = 0;
    private long lastAutoFire = 0;
    private boolean scanning = false;

    // web + listen mode
    public static MainActivity instance;

    // listen mode (fact-checker)
    private SttLoop stt;
    private boolean listenOn = false;
    private final StringBuilder transcriptBuf = new StringBuilder();
    private int wordsSinceCheck = 0;
    private TextView liveView;
    private TextView modeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        buildUi();
        initTts();
        requestCamera();
        SettingsServer server = new SettingsServer(this, this::statusJson);
        boolean httpUp = server.start();
        final StringBuilder sb = new StringBuilder();
        sb.append(diagStorage()).append('\n')
          .append("http ").append(httpUp ? "up :8080" : "FAILED").append('\n')
          .append("wifi ").append(wifiIp() != null ? wifiIp() : "not connected").append('\n')
          .append("net ").append(AiRouter.probe(this)).append('\n')
          .append("cam ").append(CameraService.cameraInfo(this)).append('\n')
          .append("tts ").append("checking");
        statusView.setText(sb.toString());
        for (String line : CameraService.fullFacts(this).split("\n")) {
            if (!line.trim().isEmpty()) Diag.log(line);
        }
        Diag.log("boot: ui up, auto=" + autoOn);
        updateModeLine();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (autoOn) auto.postDelayed(tick, SCAN_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        auto.removeCallbacks(tick);
        if (listenOn) toggleListen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(24, 16, 24, 16);

        TextView title = new TextView(this);
        title.setText("SIDEKICK");
        title.setTextColor(GREEN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(22);

        statusView = new TextView(this);
        statusView.setTextColor(GREEN_DIM);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextSize(12);
        statusView.setLineSpacing(2, 1);

        liveView = new TextView(this);
        liveView.setTextColor(GREEN_MID);
        liveView.setTypeface(Typeface.MONOSPACE);
        liveView.setTextSize(13);
        liveView.setLineSpacing(2, 1);
        liveView.setPadding(0, 10, 0, 6);

        questionView = new TextView(this);
        questionView.setTextColor(GREEN_MID);
        questionView.setTypeface(Typeface.MONOSPACE);
        questionView.setTextSize(15);
        questionView.setPadding(0, 12, 0, 6);

        answerView = new TextView(this);
        answerView.setTextColor(GREEN);
        answerView.setTypeface(Typeface.MONOSPACE);
        answerView.setTextSize(19);
        answerView.setLineSpacing(4, 1);

        TextView hint = new TextView(this);
        hint.setText("\u25B6 TAP: capture  \u25B2\u25BC\u25C0\u25B6: scroll\nmodes: phone page :8080");
        hint.setTextColor(GREEN_DIM);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setTextSize(11);
        hint.setPadding(0, 16, 0, 0);
        hint.setGravity(Gravity.BOTTOM);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(liveView);
        inner.addView(questionView);
        inner.addView(answerView);
        scroller.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(title);
        root.addView(statusView);
        modeView = new TextView(this);
        modeView.setTextColor(GREEN);
        modeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        modeView.setTextSize(14);
        modeView.setPadding(0, 6, 0, 10);
        root.addView(modeView);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(hint);
        setContentView(root);
    }

    // ---------- Auto-detect loop: glance -> motion/stillness analysis ----------

    private void autoScan() {
        if (!autoOn) return;
        if (!busy && !scanning && hasCameraPermission()) {
            scanning = true;
            CameraService.captureScan(this, new CameraService.Callback() {
                @Override public void onJpeg(byte[] jpeg) {
                    scanning = false;
                    float[] frame = decodeSmall(jpeg);
                    if (frame != null && prevFrame != null && frame.length == prevFrame.length) {
                        float diff = meanAbsDiff(frame, prevFrame);
                        if (diff > MOTION_DIFF) {
                            motionRun++;
                            stillRun = 0;
                        } else if (diff < STILL_DIFF) {
                            stillRun++;
                        }
                        // 16-20 = gray zone: leave counters unchanged (noise tolerance)
                        Diag.log("scan: d=" + String.format(Locale.US, "%.1f", diff)
                                + " motion=" + motionRun + " still=" + stillRun);
                        maybeFire();
                    }
                    prevFrame = frame;
                }
                @Override public void onError(String message) {
                    scanning = false;
                    Diag.log("scan: CAPTURE FAIL " + message);
                }
            });
        }
        auto.postDelayed(tick, SCAN_MS);
    }

    private void maybeFire() {
        long now = System.currentTimeMillis();
        if (motionRun >= NEED_MOTION && stillRun >= NEED_STILL
                && now - lastAutoFire > AUTO_COOLDOWN_MS && !busy) {
            lastAutoFire = now;
            motionRun = 0;
            stillRun = 0;
            Diag.log("auto: TRIGGER fired (motion+still pattern)");
            runOnUiThread(() -> {
                answerView.setText("AUTO \u2014 reading your writing...");
                fireCapture(true);
            });
        }
    }

    private static float[] decodeSmall(byte[] jpeg) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 64) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o2);
            if (bmp == null) return null;
            int w = Math.max(1, bmp.getWidth() / 4);
            int h = Math.max(1, bmp.getHeight() / 4);
            Bitmap small = Bitmap.createScaledBitmap(bmp, w, h, true);
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            float[] gray = new float[w * h];
            for (int i = 0; i < px.length; i++) {
                int r = (px[i] >> 16) & 0xff, g = (px[i] >> 8) & 0xff, b = px[i] & 0xff;
                gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
            }
            bmp.recycle();
            if (small != bmp) small.recycle();
            return gray;
        } catch (Exception e) {
            return null;
        }
    }

    private static float meanAbsDiff(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return sum / a.length;
    }

    // ---------- Capture + ask ----------

    private void fireCapture(final boolean isAuto) {
        if (busy) return;
        busy = true;
        questionView.setText("");
        motionRun = 0;
        stillRun = 0;
        prevFrame = null;
        Diag.log("capture: " + (isAuto ? "AUTO" : "tap"));
        if (!isAuto) answerView.setText("Capturing...");
        CameraService.capture(this, new CameraService.Callback() {
            @Override public void onJpeg(byte[] jpeg) {
                jpeg = CameraService.boostBrightness(jpeg, 2.2f);
                saveLastCapture(jpeg);
                answerView.setText("Thinking... (" + (jpeg.length / 1024) + "KB)");
                AiRouter.askAboutPhoto(MainActivity.this, jpeg, new AiRouter.Callback() {
                    @Override public void onAnswer(String q, String a, String provider) {
                        busy = false;
                        Diag.log("ai: answered via " + provider + " len=" + (a == null ? 0 : a.length()));
                        boolean noText = a != null && a.startsWith("No handwriting");
                        if (isAuto && noText) {
                            Diag.log("auto: no text (quiet)");
                            return; // auto fires stay quiet on misses
                        }
                        if (q != null && !q.isEmpty()) questionView.setText("Q: " + q);
                        answerView.setText(a);
                        logEpisode(q, a, provider);
                        speak(a);
                    }
                    @Override public void onError(String message) {
                        busy = false;
                        Diag.log("ai: ERROR " + message);
                        if (!isAuto) answerView.setText("ERROR: " + message);
                    }
                });
            }
            @Override public void onError(String message) {
                busy = false;
                answerView.setText("CAMERA ERROR: " + message);
            }
        });
    }

    // ---------- Input ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                long now = System.currentTimeMillis();
                if (now - lastTap < 400) return true;
                lastTap = now;
                fireCapture(false);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            scroller.smoothScrollBy(0, -160);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            scroller.smoothScrollBy(0, 160);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            scroller.smoothScrollBy(0, keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -320 : 320);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - lastTap < 400) return true;
            lastTap = now;
            fireCapture(false);
            return true;
        }
        return super.onTouchEvent(event);
    }

    // ---------- Support ----------

    // ---------- Listen mode (conversation fact-checker) ----------

    public void toggleAuto() {
        autoOn = !autoOn;
        if (autoOn) {
            motionRun = 0; stillRun = 0; prevFrame = null;
            auto.removeCallbacks(tick);
            auto.postDelayed(tick, SCAN_MS);
        } else {
            auto.removeCallbacks(tick);
        }
        updateModeLine();
        Diag.log("auto " + (autoOn ? "ON" : "OFF"));
    }

    private void updateModeLine() {
        modeView.setText("AUTO " + (autoOn ? "ON" : "OFF")
                + "  \u00B7  LISTEN " + (listenOn ? "ON" : "OFF")
                + "  \u00B7  " + batteryPct());
    }

    public void toggleListen() {
        if (!listenOn) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11);
                return;
            }
            try {
                int pct = Integer.parseInt(batteryPct().replace("%", ""));
                if (pct <= 15) {
                    statusView.append("\nlisten: battery too low");
                    return;
                }
            } catch (Exception ignored) {}
            if (stt == null) {
                stt = new SttLoop(this);
                stt.setCallback(this::onTranscript);
            }
            listenOn = true;
            stt.start();
            updateModeLine();
        } else {
            listenOn = false;
            if (stt != null) stt.stop();
            updateModeLine();
        }
    }

    private void onTranscript(String text, String error) {
        if (error != null) { Diag.log("stt: cb err " + error); return; }
        if (text == null || text.isEmpty()) return;
        Diag.log("stt: \"" + (text.length() > 60 ? text.substring(0, 60) + "..." : text) + "\"");
        transcriptBuf.append(text.trim()).append(' ');
        if (transcriptBuf.length() > 1500) transcriptBuf.delete(0, transcriptBuf.length() - 1500);
        int words = text.trim().split("\\s+").length;
        wordsSinceCheck += words;
        if (wordsSinceCheck >= 30) {
            wordsSinceCheck = 0;
            final String recent = transcriptBuf.toString();
            AiRouter.factCheck(this, recent, result -> {
                if (result == null || result.contains("NO_FLAG")) return;
                showFlag(result);
            });
        }
    }

    private void showFlag(String flags) {
        String cur = liveView.getText().toString();
        String[] parts = cur.split("\n");
        String prev = parts.length > 0 ? parts[0] : "";
        StringBuilder nv = new StringBuilder();
        for (String line : flags.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            nv.append("\u25C6 ").append(t).append('\n');
        }
        if (prev.length() > 0) nv.append(prev);
        liveView.setText(nv.toString().trim());
        Diag.log("FLAG shown");
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void initTts() {
        try {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                runOnUiThread(() -> statusView.append("\ntts " + (ttsReady ? "ready" : "unavailable")));
            });
        } catch (Exception e) {
            ttsReady = false;
        }
    }

    private void requestCamera() {
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 10);
        }
    }

    private void saveLastCapture(byte[] jpeg) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), "last_capture.jpg"));
            fos.write(jpeg);
            fos.close();
        } catch (Exception ignored) {}
    }

    private void speak(String text) {
        if (ttsReady && tts != null && text != null && !text.isEmpty()) {
            try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sidekick-answer"); } catch (Exception ignored) {}
        }
    }

    // ---------- Episode log (GlassMemory seed) ----------

    private void logEpisode(String q, String a, String provider) {
        try {
            File dir = new File(getFilesDir(), "episodes");
            dir.mkdirs();
            String day = new SimpleDateFormat("yyyy-MM-DD", Locale.US).format(new Date()).replace("DD", "dd");
            File f = new File(dir, day + ".jsonl");
            String line = "{\"t\":" + System.currentTimeMillis()
                    + ",\"q\":" + jsonStr(q) + ",\"a\":" + jsonStr(a)
                    + ",\"p\":" + jsonStr(provider) + "}\n";
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    // ---------- Diagnostics ----------

    private String diagStorage() {
        try {
            File f = new File(getFilesDir(), "probe.txt");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(new byte[1024]);
            fos.close();
            boolean ok = f.length() == 1024;
            f.delete();
            return "storage " + (ok ? "ok" : "FAIL");
        } catch (Exception e) {
            return "storage FAIL: " + e.getMessage();
        }
    }

    private String batteryPct() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0) return "--";
            return Math.round(level * 100f / scale) + "%";
        } catch (Exception e) {
            return "--";
        }
    }

    @SuppressWarnings("deprecation")
    private String wifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }

    private String statusJson() {
        return "{\"app\":\"sidekick\",\"version\":\"0.3.2\""
            + ",\"battery\":\"" + batteryPct() + "\""
            + ",\"ip\":\"" + (wifiIp() != null ? wifiIp() : "null") + "\""
            + ",\"auto\":" + autoOn
            + ",\"listen\":" + listenOn
            + ",\"deepinfra\":" + Prefs.has(this, Prefs.K_DEEPINFRA)
            + ",\"zai\":" + Prefs.has(this, Prefs.K_ZAI) + "}";
    }
}
