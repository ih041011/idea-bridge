package ideabridge;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A small append-only log next to the token file.
 *
 * IDEA's own log is not a dependable place to look: its file name varies per install and it
 * rotates, so a message could be gone by the time anyone looks. Owning the file means the path
 * can simply be advertised in endpoint.json and read back through log.tail.
 *
 * Logging must never be the reason a command fails, so every failure here is swallowed.
 */
final class BridgeLog {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static volatile Path file;

    private BridgeLog() {
    }

    static void init(Path home) {
        file = home.resolve("bridge.log");
    }

    static void info(String message) {
        write("INFO ", message, null);
    }

    static void warn(String message) {
        write("WARN ", message, null);
    }

    static void error(String message, Throwable cause) {
        write("ERROR", message, cause);
    }

    private static void write(String level, String message, Throwable cause) {
        String body = message;
        if (cause != null) {
            StringWriter writer = new StringWriter();
            writer.append(message).append(System.lineSeparator());
            cause.printStackTrace(new PrintWriter(writer));
            body = writer.toString();
        }

        // Also to stdout: when the IDE is started from a terminal this is where it is seen first.
        System.out.println("[idea-bridge] " + level.trim() + " " + message);

        Path target = file;
        if (target == null) {
            return;
        }
        try {
            rotateIfLarge(target);
            String line = TIMESTAMP.format(LocalDateTime.now()) + " " + level + " " + body
                    + System.lineSeparator();
            Files.write(target, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // deliberately ignored: see the class comment
        }
    }

    private static void rotateIfLarge(Path target) throws IOException {
        if (Files.exists(target) && Files.size(target) > MAX_BYTES) {
            Files.move(target, target.resolveSibling("bridge.log.1"), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}