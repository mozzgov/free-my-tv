package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTPS GET that gets past a DNS that answers 127.0.0.1: some firmware (a Xiaomi MiTV-MOSR1, Android 11, in Belarus)
 * does that for api.themoviedb.org whatever the router or Private DNS say. Then the address comes from DNS over HTTPS
 * (Google, else Cloudflare, by IP), and the request goes over our own TLS socket: the SNI and the certificate check
 * keep the real host name, which Android's HttpURLConnection can't do when the URL holds an IP.
 */
final class Http {
    private Http() {}

    static final class Response implements Closeable {
        final int code;
        final InputStream body;
        private final Closeable owner;

        Response(int code, InputStream body, Closeable owner) {
            this.code = code;
            this.body = body;
            this.owner = owner;
        }

        @Override public void close() {
            try {
                owner.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final long TTL = 10 * 60_000L;
    private static final Map<String, String> resolved = new HashMap<>();
    private static final Map<String, Long> resolvedAt = new HashMap<>();

    static Response get(String url, Map<String, String> headers, int readTimeout) throws IOException {
        URL u = new URL(url);
        String ip = "https".equals(u.getProtocol()) ? bypass(u.getHost()) : null;
        if (ip == null) return plain(u, headers, readTimeout);
        return raw(u, ip, headers, readTimeout);
    }

    private static Response plain(URL u, Map<String, String> headers, int readTimeout) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        int code = c.getResponseCode();
        return new Response(code, code == 200 ? c.getInputStream() : null, c::disconnect);
    }

    /** The real address when the system's DNS sends this host to the TV itself, else null. */
    private static String bypass(String host) throws IOException {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (!a.isLoopbackAddress() && !a.isAnyLocalAddress()) return null;
            }
        } catch (java.net.UnknownHostException ignored) {
            // blocked by name: try the same way
        }
        synchronized (resolved) {
            Long at = resolvedAt.get(host);
            if (at != null && System.currentTimeMillis() - at < TTL) return resolved.get(host);
        }
        String ip = doh("https://8.8.8.8/resolve?name=", host);
        if (ip == null) ip = doh("https://1.1.1.1/dns-query?name=", host);
        if (ip == null) throw new IOException(host + " is blocked by the TV's DNS, and DNS over HTTPS didn't answer");
        AppLog.w(host + ": the TV's DNS answers 127.0.0.1, using " + ip + " (DNS over HTTPS)");
        synchronized (resolved) {
            resolved.put(host, ip);
            resolvedAt.put(host, System.currentTimeMillis());
        }
        return ip;
    }

    private static String doh(String base, String host) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + URLEncoder.encode(host, "UTF-8") + "&type=A").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Accept", "application/dns-json");
            if (c.getResponseCode() != 200) return null;
            JSONArray answers = new JSONObject(read(c.getInputStream())).optJSONArray("Answer");
            if (answers == null) return null;
            for (int i = 0; i < answers.length(); i++) {
                JSONObject a = answers.getJSONObject(i);
                String data = a.optString("data");
                if (a.optInt("type") == 1 && !data.startsWith("127.") && !data.equals("0.0.0.0")) return data;
            }
        } catch (Exception ignored) {
            // the other resolver, or none
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
    }

    /** HTTP/1.0, so the body is never chunked and ends when the server closes. */
    private static Response raw(URL u, String ip, Map<String, String> headers, int readTimeout) throws IOException {
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        Socket tcp = new Socket();
        tcp.connect(new InetSocketAddress(ip, port), 8000);
        tcp.setSoTimeout(readTimeout);
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tcp, host, port, true);
        try {
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                throw new SSLPeerUnverifiedException("certificate isn't for " + host);
            }
            String file = u.getFile().isEmpty() ? "/" : u.getFile();
            StringBuilder req = new StringBuilder("GET ").append(file).append(" HTTP/1.0\r\nHost: ").append(host)
                    .append("\r\nConnection: close\r\nUser-Agent: FreeMyTV\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            OutputStream out = s.getOutputStream();
            out.write(req.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = new BufferedInputStream(s.getInputStream());
            String status = line(in);
            String[] parts = status.split(" ");
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("bad HTTP answer from " + host);
            int code = Integer.parseInt(parts[1]);
            for (String h = line(in); !h.isEmpty(); h = line(in)) {
                if (h.toLowerCase(Locale.ROOT).startsWith("transfer-encoding:") && h.toLowerCase(Locale.ROOT).contains("chunked")) {
                    throw new IOException("chunked answer from " + host);
                }
            }
            return new Response(code, code == 200 ? in : null, s);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int ch = in.read(); ch != '\n'; ch = in.read()) {
            if (ch < 0) throw new IOException("connection closed");
            if (ch != '\r') b.write(ch);
        }
        return b.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
