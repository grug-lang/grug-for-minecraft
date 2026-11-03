package net.grug.minecraft.core;

/**
 * The server a CI client joins as the game starts, read from {@code GRUG_CI_CONNECT} as {@code
 * host:port} (#167).
 */
public final class GrugConnectAddress {
    private final String host;
    private final int port;

    private GrugConnectAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Parses {@code host:port}, failing with a message that names the variable and the value. */
    public static GrugConnectAddress parse(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw badAddress(value, null);
        }
        try {
            return new GrugConnectAddress(
                    value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        } catch (NumberFormatException e) {
            throw badAddress(value, e);
        }
    }

    private static IllegalStateException badAddress(String value, Exception cause) {
        return new IllegalStateException(
                "GRUG_CI_CONNECT must be host:port, such as 127.0.0.1:25565, but it is '"
                        + value
                        + "'.",
                cause);
    }
}
