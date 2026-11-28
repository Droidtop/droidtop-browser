package dev.droidtop.browser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Owned loopback sink for startup/unowned channels. Never reads, resolves, replies or forwards. */
final class RejectingProxy implements AutoCloseable {
    private final ServerSocket listener;
    private volatile boolean closed;

    static RejectingProxy open() throws IOException {
        ServerSocket listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 8);
            return new RejectingProxy(listener);
        } catch (IOException failure) {
            listener.close();
            throw failure;
        }
    }

    private RejectingProxy(ServerSocket listener) {
        this.listener = listener;
        Thread accept = new Thread(() -> {
            while (!closed) {
                try (Socket ignored = listener.accept()) {
                    // Closing without reading also denies CONNECT; no origin connection is ever made.
                } catch (IOException failure) {
                    // A broken listener must not be replaced by a foreign process on this port.
                    // Keep the descriptor reserved until native shutdown or owned process retirement.
                    break;
                }
            }
        }, "droidtop-reject-proxy");
        accept.setDaemon(true);
        accept.start();
    }

    int port() { return listener.getLocalPort(); }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
    }
}
