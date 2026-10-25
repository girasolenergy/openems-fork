package io.openems.common.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.Deflater;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.permessage_deflate.PerMessageDeflateExtension;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

/**
 * PoC: wire compatibility between Java-WebSocket and Jetty WebSocket (core
 * API), and the effect of the permessage-deflate compression level.
 */
public class JettyInteropPocTest {

	private static final int MESSAGES = 50;
	private static final int MAX_MESSAGE_SIZE = 10 * 1024 * 1024;
	private static final String EXTENSIONS_HEADER = "Sec-WebSocket-Extensions";

	/**
	 * Generates a JSON-RPC notification similar to Edge timestamped data.
	 *
	 * @param random the {@link Random} for the channel values
	 * @return the JSON string
	 */
	private static String payload(Random random) {
		var b = new StringBuilder(
				"{\"jsonrpc\":\"2.0\",\"method\":\"timestampedData\",\"params\":{\"2026-10-25T00:00:00Z\":{");
		for (var i = 0; i < 400; i++) {
			if (i > 0) {
				b.append(',');
			}
			b.append("\"meter").append(i % 20).append("/ActivePowerL").append(i % 3 + 1).append('_').append(i)
					.append("\":").append(random.nextInt(20000) - 10000);
		}
		return b.append("}}}").toString();
	}

	private static List<String> payloads() {
		var random = new Random(42);
		var result = new ArrayList<String>();
		for (var i = 0; i < MESSAGES; i++) {
			result.add(payload(random));
		}
		return result;
	}

	private static void echo(List<String> payloads, Consumer<String> send, BlockingQueue<String> received)
			throws InterruptedException {
		for (var p : payloads) {
			send.accept(p);
			assertEquals(p, received.poll(5, TimeUnit.SECONDS));
		}
	}

	private static long rawBytes(List<String> payloads) {
		return payloads.stream().mapToLong(String::length).sum();
	}

	/**
	 * Minimal text-message {@link FrameHandler}: aggregates fragmented text frames
	 * and hands complete messages to a callback.
	 */
	public static class TextHandler implements FrameHandler {
		private final BiConsumer<CoreSession, String> onText;
		private final StringBuilder buffer = new StringBuilder();
		private CoreSession session;

		public TextHandler(BiConsumer<CoreSession, String> onText) {
			this.onText = onText;
		}

		@Override
		public void onOpen(CoreSession session, Callback callback) {
			this.session = session;
			callback.succeeded();
			session.demand();
		}

		@Override
		public void onFrame(Frame frame, Callback callback) {
			switch (frame.getOpCode()) {
			case OpCode.TEXT, OpCode.CONTINUATION -> {
				this.buffer.append(frame.getPayloadAsUTF8());
				if (frame.isFin()) {
					var message = this.buffer.toString();
					this.buffer.setLength(0);
					this.onText.accept(this.session, message);
				}
			}
			default -> {
				// Control frames are handled by CoreSession
			}
			}
			callback.succeeded();
			this.session.demand();
		}

		@Override
		public void onError(Throwable cause, Callback callback) {
			callback.succeeded();
		}

		@Override
		public void onClosed(CloseStatus closeStatus, Callback callback) {
			callback.succeeded();
		}
	}

	private static void sendText(CoreSession session, String message) {
		session.sendFrame(new Frame(OpCode.TEXT, message), Callback.NOOP, false);
	}

	/* ---------------- Jetty core server ---------------- */

	/**
	 * Starts a Jetty core echo server.
	 *
	 * @param deflateLevel the compression level; null to refuse permessage-deflate
	 * @return the started {@link Server}
	 */
	private static Server startJettyServer(Integer deflateLevel) throws Exception {
		var server = new Server();
		var connector = new ServerConnector(server);
		connector.setPort(0);
		server.addConnector(connector);
		var components = deflateLevel == null //
				? new WebSocketComponents() //
				: new WebSocketComponents(null, null, null, null,
						new DeflaterPool(CompressionPool.DEFAULT_CAPACITY, deflateLevel, true));
		var handler = new WebSocketUpgradeHandler(components, h -> h.addMapping("/",
				WebSocketNegotiator.from((req, resp, cb) -> {
					if (deflateLevel == null) {
						resp.setExtensions(List.of());
					}
					return new TextHandler(JettyInteropPocTest::sendText);
				}, (frameHandler, req, resp) -> (FrameHandler) frameHandler)));
		handler.getConfiguration().setMaxTextMessageSize(MAX_MESSAGE_SIZE);
		server.setHandler(handler);
		server.start();
		return server;
	}

	private static URI uri(Server server) {
		return URI.create("ws://localhost:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/");
	}

	/* ---------------- Java-WebSocket client ---------------- */

	/**
	 * Counts bytes on the wire as seen by the client.
	 */
	public static class CountingSocket extends Socket {
		private final AtomicLong sent = new AtomicLong();
		private final AtomicLong received = new AtomicLong();

		@Override
		public InputStream getInputStream() throws IOException {
			return new FilterInputStream(super.getInputStream()) {
				@Override
				public int read() throws IOException {
					var b = super.read();
					if (b >= 0) {
						CountingSocket.this.received.incrementAndGet();
					}
					return b;
				}

				@Override
				public int read(byte[] buf, int off, int len) throws IOException {
					var n = super.read(buf, off, len);
					if (n > 0) {
						CountingSocket.this.received.addAndGet(n);
					}
					return n;
				}
			};
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			return new FilterOutputStream(super.getOutputStream()) {
				@Override
				public void write(int b) throws IOException {
					CountingSocket.this.sent.incrementAndGet();
					this.out.write(b);
				}

				@Override
				public void write(byte[] buf, int off, int len) throws IOException {
					CountingSocket.this.sent.addAndGet(len);
					this.out.write(buf, off, len);
				}
			};
		}
	}

	private static class JwsClient extends WebSocketClient {
		private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
		private final CompletableFuture<String> extensions = new CompletableFuture<>();

		JwsClient(URI uri, Draft draft) {
			super(uri, draft);
		}

		@Override
		public void onOpen(ServerHandshake handshake) {
			this.extensions.complete(String.valueOf(handshake.getFieldValue(EXTENSIONS_HEADER)));
		}

		@Override
		public void onMessage(String message) {
			this.received.add(message);
		}

		@Override
		public void onClose(int code, String reason, boolean remote) {
			this.extensions.complete("CLOSED " + code + " " + reason);
		}

		@Override
		public void onError(Exception ex) {
			ex.printStackTrace();
		}
	}

	/* ---------------- Java-WebSocket server ---------------- */

	private static WebSocketServer startJwsServer(Draft draft) throws Exception {
		var started = new CompletableFuture<Void>();
		var server = new WebSocketServer(new InetSocketAddress("localhost", 0), List.of(draft)) {
			@Override
			public void onOpen(WebSocket conn, ClientHandshake handshake) {
			}

			@Override
			public void onMessage(WebSocket conn, String message) {
				conn.send(message);
			}

			@Override
			public void onClose(WebSocket conn, int code, String reason, boolean remote) {
			}

			@Override
			public void onError(WebSocket conn, Exception ex) {
				ex.printStackTrace();
			}

			@Override
			public void onStart() {
				started.complete(null);
			}
		};
		server.setReuseAddr(true);
		server.start();
		started.get(5, TimeUnit.SECONDS);
		return server;
	}

	/**
	 * Old Edge (Java-WebSocket client, offers permessage-deflate) to new Backend
	 * (Jetty core server). Measures bytes on the wire per compression level.
	 */
	@Test
	@SuppressWarnings("deprecation") // setSocket() is the simplest way to count bytes on the wire
	public void testJavaWebSocketClientToJettyServer() throws Exception {
		var payloads = payloads();
		System.out.printf("%n[JWS client -> Jetty core server] raw payload: %,d bytes x2 (echo)%n",
				rawBytes(payloads));
		var levels = new ArrayList<Integer>();
		levels.add(null);
		levels.addAll(List.of(Deflater.BEST_SPEED, Deflater.DEFAULT_COMPRESSION, Deflater.BEST_COMPRESSION));
		for (var level : levels) {
			var server = startJettyServer(level);
			try {
				var client = new JwsClient(uri(server), new Draft_6455(new PerMessageDeflateExtension()));
				var socket = new CountingSocket();
				client.setSocket(socket);
				assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
				var extensions = client.extensions.get(5, TimeUnit.SECONDS);
				echo(payloads, client::send, client.received);
				client.closeBlocking();
				System.out.printf("  level=%-5s ext=[%s] client sent=%,d recv=%,d bytes%n", level, extensions,
						socket.sent.get(), socket.received.get());
			} finally {
				server.stop();
			}
		}
	}

	/**
	 * New Edge (Jetty core client, offers permessage-deflate) to old Backend
	 * (Java-WebSocket server, with and without permessage-deflate).
	 */
	@Test
	public void testJettyClientToJavaWebSocketServer() throws Exception {
		var payloads = payloads();
		System.out.printf("%n[Jetty core client -> JWS server]%n");
		var drafts = List.<Draft>of(new MyDraft6455(), new Draft_6455(new PerMessageDeflateExtension()));
		var client = new WebSocketCoreClient();
		client.start();
		try {
			for (var draft : drafts) {
				var server = startJwsServer(draft);
				try {
					var received = new LinkedBlockingQueue<String>();
					var uri = URI.create("ws://localhost:" + server.getPort() + "/");
					var request = CoreClientUpgradeRequest.from(client, uri,
							new TextHandler((session, message) -> received.add(message)));
					request.addExtensions("permessage-deflate");
					var configuration = new Configuration.ConfigurationCustomizer();
					configuration.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
					request.setConfiguration(configuration);
					var session = client.connect(request).get(5, TimeUnit.SECONDS);
					var extensions = session.getNegotiatedExtensions();
					echo(payloads, p -> sendText(session, p), received);
					session.close(CloseStatus.NORMAL, "done", Callback.NOOP);
					System.out.printf("  server draft=%-12s ext=%s echo OK (%d msgs)%n",
							draft.getClass().getSimpleName(), extensions, payloads.size());
				} finally {
					server.stop(1000);
				}
			}
		} finally {
			client.stop();
		}
	}

	/**
	 * Old Edge (Java-WebSocket client) to old Backend (Java-WebSocket server with
	 * permessage-deflate), for reference.
	 */
	@Test
	public void testJavaWebSocketBaseline() throws Exception {
		var payloads = payloads();
		System.out.printf("%n[JWS client -> JWS server (deflate)]%n");
		var server = startJwsServer(new Draft_6455(new PerMessageDeflateExtension()));
		try {
			var client = new JwsClient(URI.create("ws://localhost:" + server.getPort() + "/"),
					new Draft_6455(new PerMessageDeflateExtension()));
			assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
			var extensions = client.extensions.get(5, TimeUnit.SECONDS);
			echo(payloads, client::send, client.received);
			System.out.printf("  ext=[%s] echo OK%n", extensions);
			client.closeBlocking();
		} finally {
			server.stop(1000);
		}
	}
}
