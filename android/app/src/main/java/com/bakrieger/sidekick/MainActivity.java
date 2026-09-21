package com.bakrieger.sidekick;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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
 * Sidekick v0.1.4 — HUD (monochrome green), temple-tap PaperChat.
 * Tap = capture handwritten question + answer.
 * LONG-PRESS (0.8s) = cycle display rotation 0/90/180/270 (persisted).
 * Settings/keys: http://<glasses-ip>:8080 in Safari on the iPhone.
 */
public class MainActivity extends Activity {

    private static final int GREEN = Color.parseColor("#00FF46");
    private static final int GREEN_MID = Color.parseColor("#00A62E");
    private static final int GREEN_DIM = Color.parseColor("#006619");
    private static final String K_ROT = "ui.rot.v2"; // new key: ignores any value saved by the broken gesture build

    private TextView statusView;
    private TextView questionView;
    private TextView answerView;
    private ScrollView scroller;
    private LinearLayout root;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean busy = false;
    private long lastTap = 0;


    private int rotation = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        buildUi();
        initTts();
        requestCamera();
        rotation = Prefs.getInt(this, K_ROT, 270); // -90 deg: corrects portrait-mounted panel
        SettingsServer server = new SettingsServer(this, this::statusJson);
        boolean httpUp = server.start();
        final StringBuilder sb = new StringBuilder();
        sb.append(diagStorage()).append('\n')
          .append("http ").append(httpUp ? "up :8080" : "FAILED").append('\n')
          .append("wifi ").append(wifiIp() != null ? wifiIp() : "not connected").append('\n')
          .append("net ").append(AiRouter.probe(this)).append('\n')
          .append("cam ").append(CameraService.cameraInfo(this)).append('\n')
          .append("rot ").append(rotation).append('\n')
          .append("tts ").append("checking");
        statusView.setText(sb.toString());
        // apply rotation after first layout
        root.post(() -> applyRotation());
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(28, 20, 28, 20);

        TextView title = new TextView(this);
        title.setText("SIDEKICK");
        title.setTextColor(GREEN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(26);

        statusView = new TextView(this);
        statusView.setTextColor(GREEN_DIM);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextSize(13);
        statusView.setLineSpacing(2, 1);

        questionView = new TextView(this);
        questionView.setTextColor(GREEN_MID);
        questionView.setTypeface(Typeface.MONOSPACE);
        questionView.setTextSize(16);
        questionView.setPadding(0, 16, 0, 8);

        answerView = new TextView(this);
        answerView.setTextColor(GREEN);
        answerView.setTypeface(Typeface.MONOSPACE);
        answerView.setTextSize(21);
        answerView.setLineSpacing(4, 1);

        TextView hint = new TextView(this);
        hint.setText("\u25B6 TAP: capture handwritten question\n\u25B2\u25BC SWIPE: scroll");
        hint.setTextColor(GREEN_DIM);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setTextSize(12);
        hint.setPadding(0, 20, 0, 0);
        hint.setGravity(Gravity.BOTTOM);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(questionView);
        inner.addView(answerView);
        scroller.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(title);
        root.addView(statusView);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(hint);
        setContentView(root);
    }

    // ---------- Rotation (glasses panels mount in different orientations) ----------

    private void applyRotation() {
        View decor = getWindow().getDecorView();
        int w = decor.getWidth();
        int hgt = decor.getHeight();
        if (w <= 0 || hgt <= 0) return;
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (rotation == 90 || rotation == 270) {
            lp.width = hgt;
            lp.height = w;
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        root.setLayoutParams(lp);
        root.setPivotX(0f);
        root.setPivotY(0f);
        switch (rotation) {
            case 90:
                root.setRotation(90);
                root.setTranslationX(w);
                root.setTranslationY(0);
                break;
            case 180:
                root.setRotation(180);
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                root.setLayoutParams(lp);
                root.setTranslationX(w);
                root.setTranslationY(hgt);
                break;
            case 270:
                root.setRotation(270);
                root.setTranslationX(0);
                root.setTranslationY(hgt);
                break;
            default:
                root.setRotation(0);
                root.setTranslationX(0);
                root.setTranslationY(0);
                break;
        }
    }

    // ---------- Input ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) triggerCapture();
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            triggerCapture();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void triggerCapture() {
        long now = System.currentTimeMillis();
        if (now - lastTap < 400) return; // debounce
        lastTap = now;
        doCapture();
    }

    private void doCapture() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera();
            return;
        }
        busy = true;
        questionView.setText("");
        answerView.setText("Capturing...");
        CameraService.capture(this, new CameraService.Callback() {
            @Override public void onJpeg(byte[] jpeg) {
                saveLastCapture(jpeg);
                answerView.setText("Thinking... (" + (jpeg.length / 1024) + "KB)");
                AiRouter.askAboutPhoto(MainActivity.this, jpeg, new AiRouter.Callback() {
                    @Override public void onAnswer(String q, String a, String provider) {
                        busy = false;
                        if (q != null && !q.isEmpty()) questionView.setText("Q: " + q);
                        answerView.setText(a);
                        logEpisode(q, a, provider);
                        speak(a);
                    }
                    @Override public void onError(String message) {
                        busy = false;
                        answerView.setText("ERROR: " + message);
                    }
                });
            }
            @Override public void onError(String message) {
                busy = false;
                answerView.setText("CAMERA ERROR: " + message);
            }
        });
    }

    private void saveLastCapture(byte[] jpeg) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), "last_capture.jpg"));
            fos.write(jpeg);
            fos.close();
        } catch (Exception ignored) {}
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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 10);
        }
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
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
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
        return "{\"app\":\"sidekick\",\"version\":\"0.1.4\""
            + ",\"battery\":\"" + batteryPct() + "\""
            + ",\"ip\":\"" + (wifiIp() != null ? wifiIp() : "null") + "\""
            + ",\"rotation\":" + rotation
            + ",\"deepinfra\":" + Prefs.has(this, Prefs.K_DEEPINFRA)
            + ",\"zai\":" + Prefs.has(this, Prefs.K_ZAI) + "}";
    }
}
