package com.bakrieger.sidekick;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * GlassMemory v1 — persistent people/topic cards.
 * Stored as JSON under filesDir/memory/. Extraction via text model;
 * retrieval = keyword scoring injected into prompts.
 */
public final class GlassMemory {

    private static final String EXTRACT_PROMPT =
        "From this conversation transcript, extract durable long-term memory. "
        + "Return ONLY a compact JSON object with optional keys people and topics, "
        + "each mapping a name/topic to a one-line fact. Example: "
        + "{\"people\":{\"Sarah\":\"works in marketing, met at conference\"},"
        + "\"topics\":{\"Henderson contract\":\"due Friday, legal reviewing\"}}. "
        + "No markdown, no arrays. Include only durable facts (roles, relationships, "
        + "projects, preferences). If nothing durable, return {}.";

    public interface ExtractCb { void onDone(int peopleCount, int topicCount); }

    private GlassMemory() {}

    private static File memFile(Context ctx, String name) {
        File dir = new File(ctx.getFilesDir(), "memory");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private static JSONObject loadObj(Context ctx, String name) {
        try {
            File f = memFile(ctx, name);
            if (f.exists()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    private static void saveObj(Context ctx, String name, JSONObject obj) {
        try {
            FileOutputStream fos = new FileOutputStream(memFile(ctx, name));
            fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            Diag.log("mem: save fail " + e.getMessage());
        }
    }

    private static void mergeInto(JSONObject store, JSONObject incoming) {
        if (incoming == null) return;
        for (String key : incoming.keySet()) {
            String fact = incoming.optString(key, "").trim();
            if (fact.isEmpty()) continue;
            String old = store.optString(key, "");
            String merged = old.isEmpty() || old.equals(fact) ? fact
                    : (fact + " / " + old);
            if (merged.length() > 300) merged = merged.substring(0, 300);
            try { store.put(key, merged); } catch (Exception ignored) {}
        }
    }

    /** Extract durable facts from a transcript and merge into memory. */
    public static void extract(final Context ctx, final String transcript, final ExtractCb cb) {
        askExtract(ctx, transcript, 0, cb);
    }

    private static void askExtract(final Context ctx, final String transcript, final int attempt, final ExtractCb cb) {
        AiRouter.askText(ctx, EXTRACT_PROMPT,
                "Transcript:\n" + transcript, 400,
                result -> {
                    if ((result == null || result.trim().isEmpty() || "{}".equals(result.trim())) && attempt == 0
                            && (transcript == null || transcript.trim().isEmpty())) {
                        Diag.log("mem: nothing to extract");
                        cb.onDone(0, 0);
                        return;
                    }
                    try {
                        String cleaned = result == null ? "" : result.trim();
                        cleaned = cleaned.replace("```json", "").replace("```", "").trim();
                        int a = cleaned.indexOf('{');
                        int b = cleaned.lastIndexOf('}');
                        if (a >= 0 && b > a) cleaned = cleaned.substring(a, b + 1);
                        JSONObject parsed = new JSONObject(cleaned);
                        JSONObject people = loadObj(ctx, "people.json");
                        JSONObject topics = loadObj(ctx, "topics.json");
                        int pBefore = people.length(), tBefore = topics.length();
                        mergeInto(people, parsed.optJSONObject("people"));
                        mergeInto(topics, parsed.optJSONObject("topics"));
                        saveObj(ctx, "people.json", people);
                        saveObj(ctx, "topics.json", topics);
                        Diag.log("mem: extracted people+" + (people.length() - pBefore)
                                + " topics+" + (topics.length() - tBefore));
                        cb.onDone(people.length(), topics.length());
                    } catch (Exception e) {
                        Diag.log("mem: parse fail " + e.getMessage());
                        cb.onDone(-1, -1);
                    }
                });
    }

    /** Top matching memory lines for a query ("name: fact"), or "". */
    public static String contextFor(Context ctx, String query) {
        try {
            if (query == null || query.trim().isEmpty()) return "";
            String q = query.toLowerCase();
            String[] words = q.split("\\W+");
            JSONObject people = loadObj(ctx, "people.json");
            JSONObject topics = loadObj(ctx, "topics.json");
            java.util.List<Object[]> scored = new java.util.ArrayList<>();
            for (String store : new String[]{"p", "t"}) {
                JSONObject obj = store.equals("p") ? people : topics;
                for (String key : obj.keySet()) {
                    double score = 0;
                    if (q.contains(key.toLowerCase())) score += 5;
                    for (String w : words) {
                        if (w.length() >= 4 && key.toLowerCase().contains(w)) score += 1;
                        String fact = obj.optString(key, "").toLowerCase();
                        if (w.length() >= 5 && fact.contains(w)) score += 0.5;
                    }
                    if (score > 0) scored.add(new Object[]{score, key + ": " + obj.optString(key, "")});
                }
            }
            scored.sort((x, y) -> Double.compare((Double) y[0], (Double) x[0]));
            StringBuilder sb = new StringBuilder();
            int n = Math.min(5, scored.size());
            for (int i = 0; i < n; i++) sb.append(scored.get(i)[1]).append('\n');
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Combined memory dump for the web page. */
    public static String memoryJson(Context ctx) {
        try {
            JSONObject out = new JSONObject();
            out.put("people", loadObj(ctx, "people.json"));
            out.put("topics", loadObj(ctx, "topics.json"));
            File ep = new File(ctx.getFilesDir(), "episodes");
            int episodes = ep.exists() ? ep.listFiles() != null ? ep.listFiles().length : 0 : 0;
            out.put("episode_files", episodes);
            return out.toString(2);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
