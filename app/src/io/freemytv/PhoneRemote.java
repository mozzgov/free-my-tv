package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home Settings' "Phone remote": a small web page on the TV's address (port 8642) with the arrows, OK, Back, Home,
 * volume, typing and opening links and apps. Off by default: then nothing listens. Every call needs the PIN shown in
 * Home Settings (five wrong ones lock it for five minutes); the keys go through Injector, started once through the
 * TV's own ADB. A foreground service in its own process (:remote), so the home screen isn't kept in memory for it.
 */
public class PhoneRemote extends Service {
    static final int PORT = 8642;
    /** Keys the page may press: arrows, OK, Back, Home, Menu, volume, mute, media, Sleep (turns the TV off). */
    private static final Set<Integer> KEYS = new HashSet<>(java.util.Arrays.asList(19, 20, 21, 22, 23, 4, 3, 82, 24, 25, 164,
            85, 89, 90, 87, 88, 223, 67, 166, 167));

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile ServerSocket server;
    private String pin = "";
    private int wrong;
    private long lockedUntil;

    private Socket injector;
    private OutputStream injectorOut;

    /** Starts or stops the service as Home Settings says. */
    static void sync(Context c) {
        Intent i = new Intent(c, PhoneRemote.class);
        if (Prefs.phoneRemote()) c.startForegroundService(i);
        else c.stopService(i);
    }

    /** "192.168.31.71:8642" (the TV's address on the home network), or empty without one. */
    static String address() {
        long now = SystemClock.elapsedRealtime();
        if (now - addressAt < 10_000 && addressAt != 0) return address;
        addressAt = now;
        address = "";
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return address = a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (Exception ignored) {
        }
        return address;
    }

    private static volatile String address = "";
    private static volatile long addressAt;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        Prefs.reload();
        Ui.applyLanguage();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", Ui.tr("Phone remote"), NotificationManager.IMPORTANCE_MIN));
        startForeground(2, new Notification.Builder(this, "remote").setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("Phone remote")).build());
        pin = Prefs.remotePin();
        new Thread(this::listen, "phone-remote").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Prefs.reload();
        if (!Prefs.phoneRemote()) stopSelf();
        pin = Prefs.remotePin();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        closeInjector();
        pool.shutdownNow();
        super.onDestroy();
    }

    private void listen() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT), 8);
            server = s;
            AppLog.w("phone remote on " + address());
            while (!s.isClosed()) {
                final Socket client = s.accept();
                pool.execute(() -> serve(client));
            }
        } catch (IOException e) {
            if (server == null || !server.isClosed()) AppLog.w("phone remote: " + e.getMessage());
        }
    }

    // MARK: - HTTP (just enough for one page and its calls)

    private void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            String request = line(in);
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            int length = 0;
            String givenPin = "";
            for (String h = line(in); !h.isEmpty(); h = line(in)) {
                String lower = h.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
                else if (lower.startsWith("x-pin:")) givenPin = h.substring(6).trim();
            }
            if (length < 0 || length > 8192) return;
            byte[] body = new byte[length];
            for (int n = 0; n < length; ) {
                int r = in.read(body, n, length - n);
                if (r < 0) return;
                n += r;
            }
            OutputStream out = s.getOutputStream();
            String path = parts[1];
            if (parts[0].equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
                send(out, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (!path.startsWith("/api/")) {
                send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            int code = checkPin(givenPin);
            if (code != 200) {
                send(out, code, "application/json", ("{\"error\":" + code + "}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            String text = new String(body, StandardCharsets.UTF_8).trim();
            String answer = api(path.substring(5), text);
            send(out, answer == null ? 400 : 200, "application/json", (answer == null ? "{\"error\":400}" : answer).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private synchronized int checkPin(String given) {
        long now = SystemClock.elapsedRealtime();
        if (now < lockedUntil) return 429;
        if (!pin.isEmpty() && constantEquals(given, pin)) {
            wrong = 0;
            return 200;
        }
        if (++wrong >= 5) {
            wrong = 0;
            lockedUntil = now + 5 * 60_000L;
            AppLog.w("phone remote: 5 wrong PINs, locked for 5 min");
        }
        return 403;
    }

    private String api(String call, String body) throws Exception {
        switch (call) {
            case "hello":
                return new JSONObject().put("ok", true).put("apps", apps()).toString();
            case "key":
            case "long": {
                int code = Integer.parseInt(body);
                if (!KEYS.contains(code)) return null;
                inject(call + " " + code);
                return "{\"ok\":true}";
            }
            case "text":
                if (body.isEmpty() || body.length() > 500) return null;
                inject("paste " + Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                return "{\"ok\":true}";
            case "url":
                if (!body.matches("https?://\\S{1,2000}")) return null;
                inject("view " + body);
                return "{\"ok\":true}";
            case "open": {
                String component = component(body);
                if (component == null) return null;
                inject("start " + component);
                return "{\"ok\":true}";
            }
            default:
                return null;
        }
    }

    /** The shelf's apps, for the page's buttons. */
    private JSONArray apps() {
        JSONArray list = new JSONArray();
        PackageManager pm = getPackageManager();
        for (String pkg : HomeApps.shelfPackages(this)) {
            Intent launch = HomeApps.launchIntent(pm, pkg);
            if (launch == null) continue;
            ResolveInfo ri = pm.resolveActivity(launch, 0);
            try {
                list.put(new JSONObject().put("pkg", pkg).put("label", ri != null ? String.valueOf(ri.loadLabel(pm)) : pkg));
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private String component(String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return null;
        Intent launch = HomeApps.launchIntent(getPackageManager(), pkg);
        return launch == null || launch.getComponent() == null ? null : launch.getComponent().flattenToString();
    }

    private byte[] page() throws IOException {
        try (InputStream in = getAssets().open("remote.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n = in.read(b); n > 0; n = in.read(b)) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 429 ? "Too Many Requests" : "Bad Request";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int ch = in.read(); ch != '\n'; ch = in.read()) {
            if (ch < 0) throw new IOException("closed");
            if (ch != '\r') b.write(ch);
            if (b.size() > 4096) throw new IOException("line too long");
        }
        return b.toString("UTF-8");
    }

    // MARK: - Injector (started once through the TV's ADB, then reached on 127.0.0.1)

    private synchronized void inject(String line) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (injectorOut == null) connectInjector();
                injectorOut.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                injectorOut.flush();
                return;
            } catch (IOException e) {
                closeInjector();
                if (attempt == 1) throw e;
            }
        }
    }

    private void connectInjector() throws IOException {
        String token = token(), version = version();
        if (tryInjector(token, version)) return;
        try (Adb adb = Adb.connect(this, 10_000, null)) {
            String apk = getApplicationInfo().sourceDir;
            adb.shell("CLASSPATH=" + apk + " setsid app_process /system/bin " + Injector.class.getName() + " " + token + " " + version
                    + " </dev/null >/dev/null 2>&1 &");
        }
        for (int i = 0; i < 30; i++) {
            SystemClock.sleep(100);
            if (tryInjector(token, version)) {
                AppLog.w("phone remote: injector started");
                return;
            }
        }
        throw new IOException("the injector didn't start");
    }

    /** Connects to a running injector; one from an older version of the app is told to quit. */
    private boolean tryInjector(String token, String version) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", Injector.PORT), 500);
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write(("auth " + token + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String hello = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (hello == null) throw new IOException("refused");
            if (!hello.equals("ok " + version)) {
                out.write("quit\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                s.close();
                SystemClock.sleep(300);
                return false;
            }
            s.setSoTimeout(0);
            injector = s;
            injectorOut = out;
            return true;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            return false;
        }
    }

    private void closeInjector() {
        try {
            if (injector != null) injector.close();
        } catch (IOException ignored) {
        }
        injector = null;
        injectorOut = null;
    }

    /** Ours alone (this process), kept so a restarted service still reaches the injector it started. */
    private String token() {
        SharedPreferences sp = getSharedPreferences("remote", MODE_PRIVATE);
        String t = sp.getString("token", "");
        if (t.length() < 32) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            t = Base64.encodeToString(b, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            sp.edit().putString("token", t).commit();
        }
        return t;
    }

    private String version() {
        try {
            return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static boolean constantEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
