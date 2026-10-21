package net.grug.minecraft.grug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.LongConsumer;

/**
 * The one HTTP request grug makes: a GET of a whole body, for the mod catalogue (#511).
 *
 * <p>It is {@link HttpURLConnection}, which every Java grug runs on has, rather than a client
 * library, because {@code core} targets Java 8 for 1.2.5 and ships as one jar with nothing beside
 * it. It follows redirects, which GitHub's release downloads answer with.
 */
public final class GrugHttp {
    /**
     * Fetches the body of a URL, or fails with an {@link IOException} that says why, and also when
     * the body is longer than {@code limit}. {@code progress} is told how many bytes have arrived
     * so far, from the thread that fetches.
     */
    public interface Fetcher {
        byte[] get(String url, long limit, LongConsumer progress) throws IOException;
    }

    /** More than any mod's zip is, so a server cannot fill the memory of the game. */
    static final long LIMIT = 128L * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private GrugHttp() {}

    /** The fetcher the game uses. */
    public static Fetcher urlConnection() {
        return (url, limit, progress) ->
                get(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, Math.min(limit, LIMIT), progress);
    }

    static byte[] get(
            String url, int connectTimeoutMs, int readTimeoutMs, long limit, LongConsumer progress)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "grug-for-minecraft");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GET " + url + " answered " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int read = in.read(chunk); read >= 0; read = in.read(chunk)) {
                    if (body.size() + (long) read > limit) {
                        throw new IOException(
                                "GET " + url + " answered more than " + limit + " bytes");
                    }
                    body.write(chunk, 0, read);
                    progress.accept(body.size());
                }
                return body.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
