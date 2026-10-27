package ideabridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * Where the bridge keeps its token, its log and the advertisement a client reads to find it.
 *
 * Everything lives in ~/.ideabridge/ rather than in the IDE's config directory, because the
 * config directory differs per install and per product, and a client should not have to guess.
 *
 * The token is the only thing standing between another local process and the ability to rewrite
 * the user's source, so it is generated once, persisted, and restricted to the owner.
 */
final class BridgeConfig {

    static final int DEFAULT_PORT = 64343;
    private static final String HOME_DIRECTORY = ".ideabridge";

    final Path home;
    final int port;
    final String token;

    private BridgeConfig(Path home, int port, String token) {
        this.home = home;
        this.port = port;
        this.token = token;
    }

    static BridgeConfig load() throws IOException {
        Path home = Paths.get(System.getProperty("user.home", "."), HOME_DIRECTORY);
        Files.createDirectories(home);
        return new BridgeConfig(home, resolvePort(), resolveToken(home));
    }

    Path endpointFile() {
        return home.resolve("endpoint.json");
    }

    Path logFile() {
        return home.resolve("bridge.log");
    }

    void writeEndpoint(Map<String, Object> info) {
        try {
            Files.write(endpointFile(), Json.write(info).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            BridgeLog.warn("could not write " + endpointFile() + ": " + e);
        }
    }

    private static int resolvePort() {
        String configured = System.getenv("IDEABRIDGE_PORT");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("ideabridge.port");
        }
        if (configured != null && !configured.isBlank()) {
            try {
                int port = Integer.parseInt(configured.trim());
                if (port > 0 && port < 65536) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default rather than refusing to start
            }
        }
        return DEFAULT_PORT;
    }

    private static String resolveToken(Path home) throws IOException {
        String fromEnvironment = System.getenv("IDEABRIDGE_TOKEN");
        if (fromEnvironment == null || fromEnvironment.isBlank()) {
            fromEnvironment = System.getenv("IDEA_BRIDGE");
        }
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }

        Path tokenFile = home.resolve("token");
        if (Files.isReadable(tokenFile)) {
            String existing = new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }

        String generated = randomToken();
        Files.write(tokenFile, generated.getBytes(StandardCharsets.UTF_8));
        restrictToOwner(tokenFile);
        return generated;
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Best effort: the java.io.File permission calls are the one form that behaves on both
     * Windows and POSIX. A token file that stays readable is a far smaller problem than a bridge
     * that refuses to start, so nothing here is allowed to throw.
     */
    private static void restrictToOwner(Path tokenFile) {
        try {
            File asFile = tokenFile.toFile();
            asFile.setReadable(false, false);
            asFile.setWritable(false, false);
            asFile.setExecutable(false, false);
            asFile.setReadable(true, true);
            asFile.setWritable(true, true);
        } catch (Throwable ignored) {
            // deliberately ignored: see the method comment
        }
    }
}