package ideabridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bridge's transport: a loopback-only HTTP server speaking small JSON envelopes.
 *
 * Bound to 127.0.0.1 and guarded by a per-install token, because this surface can
 * rewrite the user's source files and start processes -- nothing else on the machine
 * should be able to reach it.
 *
 * Startup is recorded in ~/.ideabridge/endpoint.json on success and in bridge.log on
 * failure. A port collision must never take the IDE down with it, so every failure
 * here is swallowed into the log and the IDE keeps running without a bridge.
 */
public final class BridgeServer {

    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private static volatile BridgeServer instance;
    private static volatile String startupFailure;

    private final BridgeConfig config;
    private final ExecutorService pool;
    private final AtomicLong requestCounter = new AtomicLong();

    private BridgeServer(BridgeConfig config, ExecutorService pool) {
        this.config = config;
        this.pool = pool;
    }

    public static void startIfNeeded(String trigger) {
        if (instance != null) {
            return;
        }
        synchronized (BridgeServer.class) {
            if (instance != null) {
                return;
            }
            try {
                BridgeConfig config = BridgeConfig.load();
                BridgeLog.init(config.home);

                HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port), 0);
                ExecutorService pool = Executors.newFixedThreadPool(4, runnable -> {
                    Thread thread = new Thread(runnable, "ideabridge-http");
                    thread.setDaemon(true);
                    return thread;
                });
                http.setExecutor(pool);

                BridgeServer bridge = new BridgeServer(config, pool);
                http.createContext("/", bridge::handle);
                http.start();

                instance = bridge;
                startupFailure = null;
                bridge.announce(trigger);
            } catch (Throwable t) {
                startupFailure = t.toString();
                BridgeLog.error("bridge failed to start (trigger=" + trigger + ")", t);
            }
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static String startupFailure() {
        return startupFailure;
    }

    public static int port() {
        BridgeServer bridge = instance;
        return bridge == null ? -1 : bridge.config.port;
    }

    private void announce(String trigger) {
        Map<String, Object> info = Json.obj();
        Json.put(info, "service", "dsh-idea-bridge");
        Json.put(info, "protocol", BridgeCommands.PROTOCOL_VERSION);
        Json.put(info, "url", "http://127.0.0.1:" + config.port);
        Json.put(info, "port", config.port);
        Json.put(info, "token", config.token);
        Json.put(info, "pid", ProcessHandle.current().pid());
        Json.put(info, "startedAt", System.currentTimeMillis());
        Json.put(info, "trigger", trigger);
        Json.put(info, "ide", ideName());
        Json.put(info, "build", buildString());
        Json.put(info, "logFile", config.logFile().toString());
        config.writeEndpoint(info);
        BridgeLog.info("listening on http://127.0.0.1:" + config.port + " (trigger=" + trigger + ")");
    }

    private void handle(HttpExchange exchange) throws IOException {
        long requestId = requestCounter.incrementAndGet();
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, Object> query = parseQuery(exchange.getRequestURI().getRawQuery());

            if ("/ping".equals(path)) {
                Map<String, Object> pong = Json.obj();
                Json.put(pong, "ok", Boolean.TRUE);
                Json.put(pong, "service", "dsh-idea-bridge");
                Json.put(pong, "protocol", BridgeCommands.PROTOCOL_VERSION);
                Json.put(pong, "authRequired", Boolean.TRUE);
                Json.put(pong, "port", config.port);
                Json.put(pong, "requests", requestId);
                send(exchange, 200, pong);
                return;
            }

            if (!"/cmd".equals(path) && !"/".equals(path) && !"/help".equals(path)) {
                send(exchange, 404, failure("unknown endpoint " + path + "; POST to /cmd"));
                return;
            }

            String presented = exchange.getRequestHeaders().getFirst("X-Bridge-Token");
            if (presented == null) {
                presented = Json.str(query.get("token"));
            }
            if (!constantTimeEquals(presented, config.token)) {
                BridgeLog.warn("rejected an unauthorized request from " + exchange.getRemoteAddress()
                        + " (pass header X-Bridge-Token or ?token=)");
                send(exchange, 401, failure("unauthorized: pass the token from ~/.ideabridge/token"));
                return;
            }

            Map<String, Object> request;
            if ("GET".equals(method)) {
                request = new LinkedHashMap<>(query);
                request.putIfAbsent("cmd", "help");
            } else {
                byte[] body = readBody(exchange);
                if (body.length == 0) {
                    request = new LinkedHashMap<>(query);
                    request.putIfAbsent("cmd", "help");
                } else {
                    Object parsed;
                    try {
                        parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
                    } catch (RuntimeException e) {
                        send(exchange, 400, failure("invalid JSON body: " + e.getMessage()));
                        return;
                    }
                    Map<String, Object> asObject = Json.asObject(parsed);
                    if (asObject == null) {
                        send(exchange, 400, failure("request body must be a JSON object"));
                        return;
                    }
                    request = asObject;
                }
            }

            Map<String, Object> response = BridgeCommands.dispatch(request);
            Json.put(response, "requestId", requestId);
            send(exchange, 200, response);
        } catch (Throwable t) {
            BridgeLog.error("request handling failed", t);
            try {
                send(exchange, 500, failure(t.toString()));
            } catch (Throwable ignored) {
                // the socket is already gone; nothing useful left to do
            }
        } finally {
            try {
                exchange.close();
            } catch (Throwable ignored) {
                // closing an already-closed exchange is not an error worth reporting
            }
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
                if (out.size() > MAX_BODY_BYTES) {
                    throw new IOException("request body exceeds " + MAX_BODY_BYTES + " bytes");
                }
            }
            return out.toByteArray();
        }
    }

    private static void send(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> failure(String message) {
        Map<String, Object> result = Json.obj();
        Json.put(result, "ok", Boolean.FALSE);
        Json.put(result, "error", message);
        return result;
    }

    static Map<String, Object> parseQuery(String rawQuery) {
        Map<String, Object> result = Json.obj();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(urlDecode(key), urlDecode(value));
        }
        return result;
    }

    private static String urlDecode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return text;
        }
    }

    /**
     * Length-independent comparison would need padded hashes; for a loopback token the
     * only realistic threat is another local process guessing, so an early length exit
     * is acceptable and the byte loop stays constant-time.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < left.length; i++) {
            difference |= left[i] ^ right[i];
        }
        return difference == 0;
    }

    private static String ideName() {
        try {
            return com.intellij.openapi.application.ApplicationInfo.getInstance().getFullApplicationName();
        } catch (Throwable t) {
            return "IntelliJ IDEA";
        }
    }

    private static String buildString() {
        try {
            return com.intellij.openapi.application.ApplicationInfo.getInstance().getBuild().asString();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}