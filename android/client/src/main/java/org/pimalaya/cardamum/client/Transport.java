package org.pimalaya.cardamum.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * URL-keyed pool of sockets for the Rust bridge. The native driver calls
 * {@link #read} / {@link #write} by name on every yield, passing the
 * endpoint URL it wants to talk to; the pool lazily opens one connection
 * per origin (scheme, host, port) and reuses it for the rest of the
 * call. A {@code tcp} (DNS resolver) or {@code http} URL gets a plain
 * socket, an {@code https} URL a TLS socket validated by the platform
 * trust store.
 *
 * <p>Not thread-safe: one {@code Transport} serves one native call.
 */
final class Transport {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final class Connection {
        final Socket socket;
        final BufferedInputStream input;
        final BufferedOutputStream output;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }
    }

    private final Map<String, Connection> connections = new HashMap<>();
    private final byte[] buffer = new byte[16 * 1024];

    /** Reads the next chunk from the URL's stream; empty signals EOF. */
    public byte[] read(String url) throws IOException {
        Connection connection = connect(url);
        int read = connection.input.read(buffer);

        if (read <= 0) {
            return new byte[0];
        }

        byte[] chunk = new byte[read];
        System.arraycopy(buffer, 0, chunk, 0, read);
        return chunk;
    }

    /** Writes and flushes every byte to the URL's stream. */
    public void write(String url, byte[] bytes) throws IOException {
        Connection connection = connect(url);
        connection.output.write(bytes);
        connection.output.flush();
    }

    /** Closes every open socket; call once the native operation returns. */
    public void close() {
        for (Connection connection : connections.values()) {
            try {
                connection.socket.close();
            } catch (IOException ignored) {
                // NOTE: best-effort; the pool is dropped either way.
            }
        }
        connections.clear();
    }

    private Connection connect(String url) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
        String origin = scheme + "://" + host + ":" + port;

        Connection connection = connections.get(origin);
        if (connection != null) {
            return connection;
        }

        // NOTE: URI keeps brackets around IPv6 literals; the socket API
        // wants the bare address.
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        plain.setSoTimeout(READ_TIMEOUT_MS);

        Socket socket;
        switch (scheme) {
            case "https":
                // NOTE: the host-aware overload keeps the peer host, so
                // TLS gets SNI and the platform validates the chain.
                socket =
                        ((SSLSocketFactory) SSLSocketFactory.getDefault())
                                .createSocket(plain, host, port, true);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                ((SSLSocket) socket).startHandshake();
                break;
            case "http":
            case "tcp":
                socket = plain;
                break;
            default:
                plain.close();
                throw new CardamumException("Unsupported transport scheme '" + scheme + "'");
        }

        connection = new Connection(socket);
        connections.put(origin, connection);
        return connection;
    }

    private static int defaultPort(String scheme) {
        switch (scheme) {
            case "https":
                return 443;
            case "http":
                return 80;
            default:
                throw new CardamumException("Transport URL '" + scheme + "' has no port");
        }
    }
}
