package com.bakrieger.sidekick;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** DeepInfra-first vision/text router with z.ai fallback. */
public final class AiRouter {

    public interface Callback {
        void onAnswer(String question, String answer, String provider);
        void onError(String message);
    }

    private static final String PROMPT =
        "This photo was taken by smart glasses. Look carefully for ANY handwriting, "
        + "hand-printed text, or written question on paper, a notebook, a whiteboard, or a screen. "
        + "Even small or partial handwriting counts. "
        + "1) Transcribe the handwriting exactly. "
        + "2) If it is a question, answer it concisely in at most 80 words. "
        + "Reply in exactly this format:\nQ: <transcribed handwriting>\nA: <answer>\n"
        + "If there is truly no handwriting anywhere, reply in exactly this format:\n"
        + "NO_TEXT: <describe what the photo shows in under 12 words>";

    private AiRouter() {}

    public static void askAboutPhoto(final Context ctx, final byte[] jpeg, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String dataUrl = CameraService.toDataUrl(jpeg);
            String[] providers = {"deepinfra", "zai"};
            StringBuilder errs = new StringBuilder();
            for (final String p : providers) {
                String key = Prefs.get(ctx, p.equals("deepinfra") ? Prefs.K_DEEPINFRA : Prefs.K_ZAI);
                Diag.log("ai: trying " + p + " key=" + (key.length() >= 8 ? "set" : "MISSING"));
                if (key.length() < 8) {
                    if (errs.length() > 0) errs.append(" | ");
                    errs.append(p).append(": key not set");
                    continue;
                }
                String baseUrl = p.equals("deepinfra")
                        ? "https://api.deepinfra.com/v1/openai"
                        : "https://api.z.ai/api/paas/v4";
                String model = p.equals("deepinfra")
                        ? "Qwen/Qwen3-VL-30B-A3B-Instruct" : "glm-4.5v";
                try {
                    String content = postChat(baseUrl, key, model, dataUrl);
                    String qTemp;
                    String aTemp;
                    if (content != null && content.contains("NO_TEXT")) {
                        qTemp = null;
                        String desc = content.contains(":") ? content.substring(content.indexOf(':') + 1).trim() : content;
                        aTemp = "No handwriting. Camera sees: " + desc;
                    } else {
                        qTemp = extract(content, "Q:");
                        aTemp = extract(content, "A:");
                        if (aTemp == null) aTemp = content != null ? content.trim() : "empty response";
                    }
                    final String q = qTemp;
                    final String a = aTemp;
                    final String prov = p;
                    ui.post(() -> cb.onAnswer(q, a, prov));
                    return;
                } catch (Exception e) {
                    if (errs.length() > 0) errs.append(" | ");
                    errs.append(p).append(": ").append(e.getMessage());
                }
            }
            final String err = errs.length() > 0 ? errs.toString() : "no providers configured";
            ui.post(() -> cb.onError(err));
        }, "sidekick-ai").start();
    }

    private static String extract(String content, String marker) {
        if (content == null) return null;
        int i = content.indexOf(marker);
        if (i < 0) return null;
        String rest = content.substring(i + marker.length()).trim();
        int j = rest.indexOf("\nA:");
        int k = rest.indexOf("\nQ:");
        int end = -1;
        if (j >= 0 && (k < 0 || j < k)) end = j;
        else if (k >= 0) end = k;
        return (end >= 0 ? rest.substring(0, end) : rest).trim();
    }

    private static String postChat(String baseUrl, String key, String model, String dataUrl) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 350);
        body.put("temperature", 0.2);
        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", dataUrl)));
        content.put(new JSONObject().put("type", "text").put("text", PROMPT));
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));
        body.put("messages", messages);

        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        long t0 = System.currentTimeMillis();
        int code = conn.getResponseCode();
        Diag.log("ai: " + conn.getURL().getHost() + " HTTP " + code + " in " + (System.currentTimeMillis() - t0) + "ms");
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        if (code >= 400) throw new Exception("HTTP " + code + " " + abbreviate(sb.toString()));
        JSONObject resp = new JSONObject(sb.toString());
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("no choices in response");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new Exception("no message in response");
        return msg.optString("content", "");
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /** Quick reachability check; returns e.g. "HTTP 401 in 640ms" or an error. */
    public static String probe(final Context ctx) {
        try {
            long t0 = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://api.deepinfra.com/v1/openai/models").openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return "HTTP " + code + " in " + (System.currentTimeMillis() - t0) + "ms";
        } catch (Exception e) {
            return "FAIL: " + e.getMessage();
        }
    }

    /** Synchronous key/connectivity test (called from the settings server thread). */
    public static String testKeySync(Context ctx) {
        String key = Prefs.get(ctx, Prefs.K_DEEPINFRA);
        if (key.length() < 8) return "NO KEY SAVED. Paste your DeepInfra key and save first.";
        try {
            JSONObject body = new JSONObject();
            body.put("model", "Qwen/Qwen3-VL-30B-A3B-Instruct");
            body.put("max_tokens", 10);
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", "Reply with exactly: OK"));
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", content));
            body.put("messages", messages);
            String baseUrl = "https://api.deepinfra.com/v1/openai";
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            if (code >= 400) return "FAILED (HTTP " + code + "): " + abbreviate(sb.toString());
            JSONObject resp = new JSONObject(sb.toString());
            String reply = resp.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
            return "SUCCESS - model replied: " + reply + " (HTTP " + code + ")";
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }
}
