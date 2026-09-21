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
        "This photo was taken by smart glasses. It may show HANDWRITING on paper/notebook/whiteboard, "
        + "OR a SCREEN (phone, tablet, computer, TV, e-reader). "
        + "If handwriting: transcribe it exactly. If a screen: read its visible content. "
        + "If the content is a question, answer it concisely (max 80 words). "
        + "If it is not a question, give the most useful brief response to the content "
        + "(max 60 words: summary, next step, or key fact). "
        + "Reply in exactly this format:\nQ: <transcribed handwriting or brief screen content>\nA: <answer>\n"
        + "If there is neither handwriting nor readable screen content, reply exactly:\n"
        + "NO_TEXT: <what the photo shows in under 12 words>";

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

    public interface FactCb { void onResult(String result); }

    private static final String FACT_PROMPT =
        "You overhear a live conversation via smart glasses. Identify clear factual claims "
        + "that are FALSE, QUESTIONABLE, or ABSURD (e.g. misclassifications like 'a duck is a fish', "
        + "wrong dates, wrong numbers, wrong names). Ignore opinions, personal plans, and speculation "
        + "about the future. If a claim is wrong, reply exactly: "
        + "FLAG: <claim under 12 words> -> <correction under 15 words> (<basis under 8 words>). "
        + "Additionally, if someone asked a question that went unanswered or a brief relevant fact "
        + "would genuinely help the conversation, reply exactly: "
        + "TIP: <helpful suggestion under 18 words>. "
        + "At most 2 lines total. If nothing qualifies, reply exactly: NO_FLAG";

    public interface TextCb { void onResult(String text); }

    /** Generic text completion through the provider chain. Returns trimmed content. */
    public static void askText(final Context ctx, final String systemPrompt, final String userText,
                               final int maxTokens, final TextCb cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String[][] providers = {
                {"deepinfra", "https://api.deepinfra.com/v1/openai", "Qwen/Qwen3.8-Flash"},
                {"deepinfra", "https://api.deepinfra.com/v1/openai", "deepseek-ai/DeepSeek-V4-Flash"},
                {"zai", "https://api.z.ai/api/paas/v4", "glm-4-air"},
            };
            for (final String[] p : providers) {
                String key = Prefs.get(ctx, p[0].equals("deepinfra") ? Prefs.K_DEEPINFRA : Prefs.K_ZAI);
                if (key.length() < 8) continue;
                try {
                    JSONObject body = new JSONObject();
                    body.put("model", p[2]);
                    body.put("max_tokens", maxTokens);
                    body.put("temperature", 0.2);
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                    messages.put(new JSONObject().put("role", "user").put("content", userText));
                    body.put("messages", messages);
                    String content = postChatRaw(p[1], key, body);
                    final String result = content == null ? "" : content.trim();
                    ui.post(() -> cb.onResult(result));
                    return;
                } catch (Exception e) {
                    Diag.log("text: " + p[2] + " FAIL " + e.getMessage());
                }
            }
            Diag.log("text: all providers failed");
            ui.post(() -> cb.onResult(""));
        }, "sidekick-text").start();
    }

    /** Fact-check a rolling transcript; result is NO_FLAG or FLAG lines. */
    public static void factCheck(final Context ctx, final String transcript, final FactCb cb) {
        askText(ctx, FACT_PROMPT, "Recent conversation transcript:\n" + transcript, 160,
                result -> cb.onResult(result == null || result.isEmpty() ? "NO_FLAG" : result));
    }

    /** POST a prebuilt chat body (text-only) and return assistant content. */
    private static String postChatRaw(String baseUrl, String key, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(45000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        long t0 = System.currentTimeMillis();
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        Diag.log("txt: HTTP " + code + " in " + (System.currentTimeMillis() - t0) + "ms");
        if (code >= 400) throw new Exception("HTTP " + code + " " + abbreviate(sb.toString()));
        JSONObject resp = new JSONObject(sb.toString());
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("no choices");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg == null ? "" : msg.optString("content", "");
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
