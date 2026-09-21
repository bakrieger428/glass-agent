package com.bakrieger.sidekick;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Ring-buffer diagnostic log, visible at http://<glasses-ip>:8080/diag */
public final class Diag {
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final int MAX = 150;

    private Diag() {}

    public static synchronized void log(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        LINES.addLast(ts + "  " + s);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
