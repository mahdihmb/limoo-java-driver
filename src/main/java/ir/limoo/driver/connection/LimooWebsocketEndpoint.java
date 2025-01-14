package ir.limoo.driver.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ir.limoo.driver.LimooDriver;
import ir.limoo.driver.entity.Workspace;
import ir.limoo.driver.event.LimooEvent;
import ir.limoo.driver.event.LimooEventListenerManager;
import ir.limoo.driver.exception.LimooAuthenticationException;
import ir.limoo.driver.exception.LimooException;
import ir.limoo.driver.util.JacksonUtils;
import org.atmosphere.wasync.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

public class LimooWebsocketEndpoint implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(LimooWebsocketEndpoint.class);

	private static final String AUTHENTICATION_FAILED_EVENT = "authentication_failed";
	private static final int MAX_CONSECUTIVE_CONNECTION_ATTEMPTS = 1000000;
	private static final int MAX_INITIAL_CONNECTION_ATTEMPTS = 2;
	private static final long DELAY_INCREASE_MILLIS = 2000;

	private final LimooEventListenerManager limooEventListenerManager;
	private final LimooDriver limooDriver;
	private Socket socket;
	private int consecutiveConnectionAttempts = 0;

	@SuppressWarnings("rawtypes")
	private RequestBuilder requestBuilder;

	public LimooWebsocketEndpoint(String websocketUrl, LimooEventListenerManager limooEventListenerManager,
								  LimooDriver limooDriver) throws LimooException {
		this.limooEventListenerManager = limooEventListenerManager;
		this.limooDriver = limooDriver;
		this.createSocket(websocketUrl);
		this.connect(true);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void createSocket(String websocketUrl) {
		Client client = ClientFactory.getDefault().newClient();

		requestBuilder = client.newRequestBuilder()
				.method(Request.METHOD.GET)
				.uri(websocketUrl)
				.transport(Request.TRANSPORT.WEBSOCKET)
				.encoder(new Encoder<JsonNode, String>() {
					@Override
					public String encode(JsonNode jsonNode) {
						return JacksonUtils.serializeObjectAsString(jsonNode);
					}
				})
				.decoder(new Decoder<String, JsonNode>() {
					@Override
					public JsonNode decode(Event e, String s) {
						try {
							return JacksonUtils.convertStringToJsonNode(s);
						} catch (IOException e1) {
							return JacksonUtils.createEmptyObjectNode();
						}
					}
				}).transport(Request.TRANSPORT.WEBSOCKET);

		Options options = client.newOptionsBuilder().reconnect(false).build();
		socket = client.create(options);
		socket.on(new Function<JsonNode>() {
			@Override
			public void on(JsonNode jsonNode) {
				if (jsonNode.get("event") != null) {
					String event = jsonNode.get("event").asText();
					handleEvent(event, jsonNode);
				}
			}
		}).on(Event.CLOSE, new Function<String>() {
			@Override
			public void on(String t) {
                reconnect();
			}
		});
	}

    private void reconnect() {
        close();
        try {
            connect(false);
        } catch (LimooException e) {
            logger.error("", e);
            System.exit(1);
        }
    }

	private void connect(boolean isInitialConnection) throws LimooException {
		while (true) {
			try {
				consecutiveConnectionAttempts++;
				attemptToConnect();
				consecutiveConnectionAttempts = 0;
				return;
			} catch (LimooAuthenticationException e) {
				throw e;
			} catch (IOException | LimooException e) {
				int allowedConnectionAttempts = isInitialConnection ? MAX_INITIAL_CONNECTION_ATTEMPTS : MAX_CONSECUTIVE_CONNECTION_ATTEMPTS;
				if (consecutiveConnectionAttempts >= allowedConnectionAttempts)
					throw (e instanceof LimooException) ? (LimooException) e : (new LimooException(e));
				try {
					Thread.sleep(getNextConnectionAttemptDelay());
				} catch (InterruptedException e1) {
					logger.error("", e1);
				}
			}
		}
	}

	private void attemptToConnect() throws IOException, LimooException {
		requestBuilder.header("Cookie", "ACCESSTOKEN=" + limooDriver.getRequester().getAccessToken());
		socket.open(requestBuilder.build());
	}

	private long getNextConnectionAttemptDelay() {
		return consecutiveConnectionAttempts * DELAY_INCREASE_MILLIS;
	}

	private void handleEvent(String event, JsonNode eventNode) {
		if (AUTHENTICATION_FAILED_EVENT.equals(event)) {
			// This normally shouldn't happen
			logger.error("Received authentication_failed upon opening websocket");
            reconnect();
		} else if (eventNode.has("data")) {
			JsonNode dataNode = eventNode.get("data");
			Workspace workspace = null;
			if (dataNode.has("workspace_id")) {
                JsonNode workspaceIdNode = dataNode.get("workspace_id");
                if (workspaceIdNode != null && !workspaceIdNode.isNull()) {
                    if (workspaceIdNode instanceof ArrayNode) {
                        for (JsonNode idNode : workspaceIdNode) {
                            try {
                                workspace = limooDriver.getWorkspaceById(idNode.asText());
                            } catch (LimooException e) {
                                logger.error("", e);
                            }
                        }
                    } else {
                        try {
                            workspace = limooDriver.getWorkspaceById(workspaceIdNode.asText());
                        } catch (LimooException e) {
                            logger.error("", e);
                        }
                    }
                }
			}
			limooEventListenerManager.newEvent(new LimooEvent(event, dataNode, workspace));
		}
	}

	@Override
	public void close() {
		socket.close();
	}
}
