package ideabridge;

/** A failure the caller caused and can fix, so it is reported without a stack trace. */
final class BridgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BridgeException(String message) {
        super(message);
    }
}