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
        "This photo may contain handwriting on paper. "
        + "1) If handwritten text is visible, transcribe it exactly. "
        + "2) If it is a question, answer it concisely in at most 80 words. "
        + "Reply in exactly this format:\nQ: <transcribed handwriting>\nA: <answer>\n"
        + "If no handwriting is visible, reply with exactly: NO_TEXT";

    private AiRouter() {}

    public static void askAboutPhoto(final Context ctx, final byte[] jpeg, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String dataUrl = CameraService.toDataUrl(jpeg);
            String[] providers = {"deepinfra", "zai"};
            String lastErr = "no providers configured";
            for (final String p : providers) {
                String key = Prefs.get(ctx, p.equals("deepinfra") ? Prefs.K_DEEPINFRA : Prefs.K_ZAI);
                if (key.length() < 8) { lastErr = "no key for " + p; continue; }
                String baseUrl = p.equals("deepinfra")
                        ? "https://api.deepinfra.com/v1/openai"
                        : "https://api.z.ai/api/paas/v4";
                String model = p.equals("deepinfra")
                        ? "Qwen/Qwen2.5-VL-72B-Instruct" : "glm-4.5v";
                try {
                    String content = postChat(baseUrl, key, model, dataUrl);
                    String qTemp;
                    String aTemp;
                    if (content != null && content.contains("NO_TEXT")) {
                        qTemp = null;
                        aTemp = "No handwriting detected. Look at the paper and tap again.";
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
                    lastErr = p + ": " + e.getMessage();
                }
            }
            final String err = lastErr;
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
        int code = conn.getResponseCode();
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
}
