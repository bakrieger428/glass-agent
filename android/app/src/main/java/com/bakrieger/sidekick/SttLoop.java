package com.bakrieger.sidekick;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Continuous STT: 8s MediaRecorder chunks -> DeepInfra whisper-large-v3-turbo. */
public class SttLoop {

    public interface Callback {
        void onTranscript(String text, String error);
    }

    private static final int CHUNK_MS = 10000;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler timer = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private MediaRecorder recorder;
    private File chunkFile;
    private Callback cb;
    private int chunkMaxAmp = 0;
    private String lastTail = "";   // previous transcript tail = whisper anti-hallucination prompt

    public SttLoop(Context c) {
        ctx = c.getApplicationContext();
    }

    public void setCallback(Callback c) { cb = c; }
    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        lastTail = "";
        Diag.log("stt: loop start");
        recordNext();
    }

    public void stop() {
        if (!running && recorder == null) return;
        running = false;
        timer.removeCallbacksAndMessages(null);
        stopRecorder();
        Diag.log("stt: loop stop");
    }

    private void stopRecorder() {
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
                recorder = null;
            }
        } catch (Exception ignored) {}
    }

    private void recordNext() {
        if (!running) return;
        try {
            stopRecorder();
            chunkFile = new File(ctx.getCacheDir(), "stt_chunk.m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(chunkFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            chunkMaxAmp = 0;
            pollAmp();
            timer.postDelayed(this::finishChunk, CHUNK_MS);
        } catch (Exception e) {
            Diag.log("stt: recorder FAIL " + e.getMessage());
            if (cb != null) ui.post(() -> cb.onTranscript(null, e.getMessage()));
            timer.postDelayed(this::recordNext, 4000);
        }
    }

    private void pollAmp() {
        if (!running || recorder == null) return;
        try {
            int a = recorder.getMaxAmplitude();
            if (a > chunkMaxAmp) chunkMaxAmp = a;
        } catch (Exception ignored) {}
        timer.postDelayed(this::pollAmp, 400);
    }

    private void finishChunk() {
        final byte[] bytes = stopRecorderAndGet();
        final int amp = chunkMaxAmp;
        if (bytes == null || bytes.length < 2500 || amp < 350) {
            // silence gate: quiet chunk = skip upload entirely
            Diag.log("stt: skip quiet chunk amp=" + amp);
            recordNext();
            return;
        }
        final String promptTail = lastTail;
        new Thread(() -> {
            try {
                final String text = transcribe(bytes, promptTail);
                if (text != null && !text.isEmpty()) {
                    lastTail = text.length() > 200 ? text.substring(text.length() - 200) : text;
                }
                if (cb != null) ui.post(() -> cb.onTranscript(text, null));
                Diag.log("stt: amp=" + amp);
            } catch (Exception e) {
                Diag.log("stt: upload FAIL " + e.getMessage());
                if (cb != null) ui.post(() -> cb.onTranscript(null, e.getMessage()));
            }
            ui.post(this::recordNext);
        }, "sidekick-stt").start();
    }

    private byte[] stopRecorderAndGet() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
            if (chunkFile != null && chunkFile.exists()) {
                byte[] buf = new byte[(int) chunkFile.length()];
                try (FileInputStream fis = new FileInputStream(chunkFile)) {
                    int read = fis.read(buf);
                    if (read > 0) return buf;
                }
            }
        } catch (Exception e) {
            Diag.log("stt: stop err " + e.getMessage());
            try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        return null;
    }

    private String transcribe(byte[] audio, String promptTail) throws Exception {
        String key = Prefs.get(ctx, Prefs.K_DEEPINFRA);
        if (key.length() < 8) throw new Exception("no deepinfra key");
        String boundary = "----sidekick" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection)
                new URL("https://api.deepinfra.com/v1/openai/audio/transcriptions").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "model", "openai/whisper-large-v3-turbo");
        if (promptTail != null && !promptTail.isEmpty()) {
            writeField(body, boundary, "prompt", promptTail);
        }
        writeFile(body, boundary, "file", "chunk.m4a", "audio/mp4", audio);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toByteArray());
        }
        long t0 = System.currentTimeMillis();
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) resp.write(b, 0, n);
        in.close();
        Diag.log("stt: whisper HTTP " + code + " in " + (System.currentTimeMillis() - t0) + "ms");
        if (code >= 400) throw new Exception("HTTP " + code + " " + resp.toString().substring(0, Math.min(120, resp.size())));
        JSONObject json = new JSONObject(resp.toString("UTF-8"));
        return json.optString("text", "").trim();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(ByteArrayOutputStream out, String boundary, String name, String filename, String type, byte[] data) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
