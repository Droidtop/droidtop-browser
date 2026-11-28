package dev.droidtop.browser;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** CI only: real owned loopback sockets, no browser/app/device or external network. */
public final class RejectingProxyTest {
    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try (RejectingProxy sink = RejectingProxy.open(); ServerSocket destination = new ServerSocket()) {
            if (sink.port() < 1024) throw new AssertionError("No ephemeral sink port");
            destination.bind(new InetSocketAddress(loopback, 0), 1);
            destination.setSoTimeout(300);
            try (ServerSocket foreign = new ServerSocket()) {
                try {
                    foreign.bind(new InetSocketAddress(loopback, sink.port()));
                    throw new AssertionError("Another service could bind the fallback port");
                } catch (BindException expected) { }
            }
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(loopback, sink.port()), 2000);
                client.setSoTimeout(2000);
                try {
                    client.getOutputStream().write(("CONNECT 127.0.0.1:" + destination.getLocalPort()
                        + " HTTP/1.1\r\nHost: ignored\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (client.getInputStream().read() != -1) throw new AssertionError("The sink replied/forwarded data");
                } catch (SocketException reset) { /* Immediate reset is also rejection. */ }
            }
            try (Socket forwarded = destination.accept()) {
                throw new AssertionError("The sink connected to the requested destination");
            } catch (SocketTimeoutException expected) { }
        }
        System.out.println("Owned startup proxy: port reserved, CONNECT rejected, no destination connection");
    }
}
