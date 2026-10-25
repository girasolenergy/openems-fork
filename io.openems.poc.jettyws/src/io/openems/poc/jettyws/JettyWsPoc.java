package io.openems.poc.jettyws;

import java.net.URI;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.permessage_deflate.PerMessageDeflateExtension;
import org.java_websocket.handshake.ServerHandshake;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PoC: runs a Jetty WebSocket (core API only) echo server inside OSGi and
 * connects to it with a Jetty core client and a Java-WebSocket client.
 */
@Component(immediate = true)
public class JettyWsPoc {

	public static final int PORT = 8099;
	private static final int MESSAGES = 50;
	private static final boolean SERVER_ONLY = Boolean.getBoolean("poc.serverOnly");

	private final Logger log = LoggerFactory.getLogger(JettyWsPoc.class);
	private Server server;
	private Thread thread;

	/**
	 * Minimal text-message FrameHandler: aggregates fragmented text frames and
	 * hands complete messages to a callback.
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
				// control frames are handled by CoreSession
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

	@Activate
	private void activate() {
		this.thread = new Thread(this::run, "JettyWsPoc");
		this.thread.start();
	}

	@Deactivate
	private void deactivate() throws Exception {
		if (this.server != null) {
			this.server.stop();
		}
	}

	private static String payload(Random random) {
		var b = new StringBuilder("{\"jsonrpc\":\"2.0\",\"method\":\"timestampedData\",\"params\":{");
		for (var i = 0; i < 400; i++) {
			if (i > 0) {
				b.append(',');
			}
			b.append("\"meter").append(i % 20).append("/ActivePower_").append(i).append("\":")
					.append(random.nextInt(20000) - 10000);
		}
		return b.append("}}").toString();
	}

	private void run() {
		try {
			final var start = System.nanoTime();
			this.server = new Server();
			var connector = new ServerConnector(this.server);
			connector.setPort(PORT);
			this.server.addConnector(connector);
			var components = new WebSocketComponents(null, null, null, null,
					new DeflaterPool(CompressionPool.DEFAULT_CAPACITY, 6, true));
			var upgradeHandler = new WebSocketUpgradeHandler(components, h -> h.addMapping("/",
					WebSocketNegotiator.from((req, resp, cb) -> new TextHandler(SERVER_ONLY //
							// Same work as Edge OnNotification for an unhandled notification
							? (session, message) -> this.log.warn("Unhandled Notification: " + message)
							: (session, message) -> session.sendFrame(new Frame(OpCode.TEXT, message), Callback.NOOP,
									false)),
							(handler, req, resp) -> (FrameHandler) handler)));
			upgradeHandler.getConfiguration().setMaxTextMessageSize(10 * 1024 * 1024);
			this.server.setHandler(upgradeHandler);
			this.server.start();
			this.log.warn("POC server (core API) started on port {} in {} ms", PORT,
					(System.nanoTime() - start) / 1_000_000);

			if (SERVER_ONLY) {
				this.log.warn("POC DONE (server only)");
				return;
			}
			var uri = URI.create("ws://localhost:" + PORT + "/");
			this.testJettyCoreClient(uri);
			this.testJavaWebSocketClient(uri);
			this.log.warn("POC DONE. Server keeps running on port {} for external tests", PORT);
		} catch (Throwable t) {
			var sw = new java.io.StringWriter();
			t.printStackTrace(new java.io.PrintWriter(sw));
			this.log.error("POC FAILED: " + sw.toString().replace('\n', '|'));
		}
	}

	private void testJettyCoreClient(URI uri) throws Exception {
		var client = new WebSocketCoreClient();
		client.start();
		try {
			var received = new LinkedBlockingQueue<String>();
			var handler = new TextHandler((session, message) -> received.add(message));
			var request = CoreClientUpgradeRequest.from(client, uri, handler);
			request.addExtensions("permessage-deflate");
			var session = client.connect(request).get(5, TimeUnit.SECONDS);
			var ext = session.getNegotiatedExtensions();
			var random = new Random(42);
			for (var i = 0; i < MESSAGES; i++) {
				var p = payload(random);
				session.sendFrame(new Frame(OpCode.TEXT, p), Callback.NOOP, false);
				if (!p.equals(received.poll(5, TimeUnit.SECONDS))) {
					throw new IllegalStateException("Jetty core client: echo mismatch at " + i);
				}
			}
			session.close(CloseStatus.NORMAL, "done", Callback.NOOP);
			this.log.warn("POC RESULT Jetty core client -> Jetty core server: OK ({} msgs) ext={}", MESSAGES, ext);
		} finally {
			client.stop();
		}
	}

	private void testJavaWebSocketClient(URI uri) throws Exception {
		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		var ext = new CompletableFuture<String>();
		var client = new org.java_websocket.client.WebSocketClient(uri,
				new Draft_6455(new PerMessageDeflateExtension())) {
			@Override
			public void onOpen(ServerHandshake handshake) {
				ext.complete(handshake.getFieldValue("Sec-WebSocket-Extensions"));
			}

			@Override
			public void onMessage(String message) {
				received.add(message);
			}

			@Override
			public void onClose(int code, String reason, boolean remote) {
				ext.complete("CLOSED " + code);
			}

			@Override
			public void onError(Exception ex) {
				JettyWsPoc.this.log.error("JWS client error", ex);
			}
		};
		if (!client.connectBlocking(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Java-WebSocket client: connect failed");
		}
		var random = new Random(42);
		for (var i = 0; i < MESSAGES; i++) {
			var p = payload(random);
			client.send(p);
			if (!p.equals(received.poll(5, TimeUnit.SECONDS))) {
				throw new IllegalStateException("Java-WebSocket client: echo mismatch at " + i);
			}
		}
		client.closeBlocking();
		this.log.warn("POC RESULT Java-WebSocket client -> Jetty core server: OK ({} msgs) ext=[{}]", MESSAGES,
				ext.get(1, TimeUnit.SECONDS));
	}
}
