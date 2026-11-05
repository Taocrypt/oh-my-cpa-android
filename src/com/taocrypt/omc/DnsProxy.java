package com.taocrypt.omc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A tiny HTTP/HTTPS forward proxy on 127.0.0.1.
 *
 * CPA and oh-my-cpa are a static linux/arm64 Go build (CGO off). On Android it has no
 * /etc/resolv.conf, so Go's own resolver asks 127.0.0.1:53 and every hostname fails.
 * Both honour HTTPS_PROXY / HTTP_PROXY, so the shell points those at this proxy:
 * Go hands over the hostname in CONNECT (or an absolute http:// URL), and Java resolves
 * it with Android's own DNS (netd), then pipes the bytes. TLS stays end to end.
 */
public final class DnsProxy {
    private static ServerSocket server;
    private static Thread acceptor;

    private DnsProxy() {}

    /** Starts (once) and returns the port, or -1 when it could not listen. */
    public static synchronized int start() {
        if (server != null && !server.isClosed()) {
            return server.getLocalPort();
        }
        try {
            final ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 128);
            server = ss;
            acceptor = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket c = ss.accept();
                        Thread t = new Thread(() -> handle(c), "omc-proxy-conn");
                        t.setDaemon(true);
                        t.start();
                    } catch (IOException e) {
                        if (ss.isClosed()) {
                            return;
                        }
                    }
                }
            }, "omc-proxy");
            acceptor.setDaemon(true);
            acceptor.start();
            return ss.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

    public static synchronized int port() {
        return server == null || server.isClosed() ? -1 : server.getLocalPort();
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    private static void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(30000);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();
            byte[] head = readHead(cin);
            if (head == null) {
                client.close();
                return;
            }
            String headStr = new String(head, StandardCharsets.ISO_8859_1);
            int eol = headStr.indexOf("\r\n");
            String first = eol < 0 ? headStr : headStr.substring(0, eol);
            String[] parts = first.split(" ");
            if (parts.length < 3) {
                reply(cout, "400 Bad Request");
                client.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            if ("CONNECT".equals(method)) {
                String hp = parts[1];
                int colon = hp.lastIndexOf(':');
                String host = colon > 0 ? hp.substring(0, colon) : hp;
                int port = colon > 0 ? Integer.parseInt(hp.substring(colon + 1)) : 443;
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }
                try {
                    upstream = new Socket();
                    upstream.connect(new InetSocketAddress(host, port), 15000);
                } catch (IOException e) {
                    reply(cout, "502 Bad Gateway");
                    client.close();
                    return;
                }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
                client.setSoTimeout(0);
                pipe(client, upstream);
                return;
            }
            // plain http://host[:port]/path through the proxy
            URI uri = URI.create(parts[1]);
            String host = uri.getHost();
            if (host == null) {
                reply(cout, "400 Bad Request");
                client.close();
                return;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(parts[0]).append(' ').append(path).append(' ').append(parts[2]).append("\r\n");
            String[] lines = headStr.substring(eol + 2).split("\r\n");
            for (String l : lines) {
                if (l.isEmpty()) {
                    continue;
                }
                String lower = l.toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-connection:") || lower.startsWith("proxy-authorization:") || lower.startsWith("connection:")) {
                    continue;
                }
                sb.append(l).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            try {
                upstream = new Socket();
                upstream.connect(new InetSocketAddress(host, port), 15000);
            } catch (IOException e) {
                reply(cout, "502 Bad Gateway");
                client.close();
                return;
            }
            upstream.getOutputStream().write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            upstream.getOutputStream().flush();
            client.setSoTimeout(0);
            pipe(client, upstream);
        } catch (Exception e) {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private static void reply(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int state = 0;
        while (buf.size() < 65536) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) {
                    return buf.toByteArray();
                }
            } else {
                state = b == '\r' ? 1 : 0;
            }
        }
        return null;
    }

    private static void pipe(final Socket a, final Socket b) {
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        Thread t = new Thread(() -> copy(b, a, done), "omc-proxy-down");
        t.setDaemon(true);
        t.start();
        copy(a, b, done);
    }

    private static void copy(Socket from, Socket to, java.util.concurrent.atomic.AtomicInteger done) {
        byte[] buf = new byte[16384];
        boolean failed = false;
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            failed = true;
        }
        try {
            to.shutdownOutput();
        } catch (IOException ignored) {
        }
        if (failed || done.incrementAndGet() >= 2) {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** For a quick desktop test: java DnsProxy */
    public static void main(String[] args) throws Exception {
        int p = start();
        System.out.println("PORT " + p);
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
