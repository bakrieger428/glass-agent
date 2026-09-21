package com.bakrieger.sidekick;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Tiny zero-dependency HTTP server on the glasses (port 8080).
 * Opens the door for: API key entry from the iPhone, and later the
 * keyboard-input channel — no Rokid cloud involved.
 */
public final class SettingsServer {

    public interface StatusSource {
        String statusJson();
    }

    private final Context ctx;
    private final StatusSource status;
    private ServerSocket server;
    private Thread acceptThread;

    public SettingsServer(Context ctx, StatusSource status) {
        this.ctx = ctx.getApplicationContext();
        this.status = status;
    }

    public boolean start() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(8080));
            acceptThread = new Thread(this::loop, "sidekick-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Socket sock = server.accept();
                Thread t = new Thread(() -> handle(sock), "sidekick-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                return; // server closed
            }
        }
    }

    private void handle(Socket sock) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) { sock.close(); return; }
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2 && parts[0].trim().equalsIgnoreCase("content-length")) {
                    try { contentLength = Integer.parseInt(parts[1].trim()); } catch (Exception ignored) {}
                }
            }
            char[] bodyChars = new char[contentLength];
            if (contentLength > 0) {
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(bodyChars, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            String body = new String(bodyChars).trim();
            String path = requestLine.split(" ")[1];
            String method = requestLine.split(" ")[0];

            if (path.startsWith("/save") && method.equals("POST")) {
                String di = param(body, "deepinfra");
                String zai = param(body, "zai");
                if (di.length() > 0) Prefs.set(ctx, Prefs.K_DEEPINFRA, di);
                if (zai.length() > 0) Prefs.set(ctx, Prefs.K_ZAI, zai);
                respond(sock, "200", "text/plain",
                        "SAVED\n\nDeepInfra key: " + mask(di) + "\nz.ai key: " + mask(zai)
                        + "\n\nReturn to your glasses. You can close this page.");
            } else if (path.startsWith("/status")) {
                respond(sock, "200", "application/json", status.statusJson());
            } else {
                respond(sock, "200", "text/html", page());
            }
        } catch (Exception ignored) {
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private static String param(String body, String name) {
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try { return URLDecoder.decode(kv[1], "UTF-8").trim(); } catch (Exception e) { return ""; }
            }
        }
        return "";
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) return "(unchanged)";
        return s.length() <= 8 ? "set" : s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }

    private static String page() {
        return "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>Sidekick Settings</title><style>"
            + "body{background:#000;color:#0f6;font-family:monospace;padding:24px;max-width:640px;margin:auto}"
            + "h1{font-size:22px}input{width:100%;padding:12px;margin:8px 0;background:#020;font:16px monospace;"
            + "color:#0f6;border:1px solid #060;border-radius:6px;box-sizing:border-box}"
            + "button{padding:12px 24px;background:#030;color:#0f6;font:16px monospace;"
            + "border:1px solid #0f6;border-radius:6px}</style></head><body>"
            + "<h1>SIDEKICK — API Keys</h1>"
            + "<p>Paste your keys. At least one is required. Values stay on your glasses.</p>"
            + "<form method='POST' action='/save'>"
            + "<label>DeepInfra API key</label>"
            + "<input name='deepinfra' type='password' autocomplete='off' placeholder='DeepInfra key'>"
            + "<label>z.ai API key (fallback)</label>"
            + "<input name='zai' type='password' autocomplete='off' placeholder='z.ai key'>"
            + "<button type='submit'>Save to glasses</button></form></body></html>";
    }

    private static void respond(Socket sock, String code, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = sock.getOutputStream();
        String head = "HTTP/1.1 " + code + " OK\r\n"
            + "Content-Type: " + type + "; charset=utf-8\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
        sock.close();
    }
}
