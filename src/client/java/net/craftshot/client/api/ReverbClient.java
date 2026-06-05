package net.craftshot.client.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

public class ReverbClient implements WebSocket.Listener {

    private static final String REVERB_APP_KEY = "jli7isugas192ycqmoch";
    private static final String REVERB_WS_URL = "wss://craftshot.net/app/" + REVERB_APP_KEY + "?protocol=7&client=java&version=1.0.0";
    private static final String AUTH_URL = "https://craftshot.net/api/v2/messages/broadcasting/auth";
    private static final Gson GSON = new Gson();
    private WebSocket webSocket;
    private final MessageListener listener;
    private final String channelName;
    private final StringBuilder messageBuffer = new StringBuilder();
    private boolean intentionalClose;
    public interface MessageListener {
        void onNewMessage(JsonObject messageData);
        void onPresenceUpdate(JsonObject presenceData);
    }

    public ReverbClient(String databaseId, MessageListener listener) {
        this.listener = listener;
        this.channelName = "private-user." + databaseId;
        System.out.println("[Reverb Debug] Initialized client for channel: " + this.channelName);
    }

    public void connect() {
        System.out.println("[Reverb Debug] Attempting to connect to WebSocket URL: " + REVERB_WS_URL);
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(REVERB_WS_URL), this).thenAccept(ws -> {
            this.webSocket = ws;
            System.out.println("[Reverb Debug] WebSocket HTTP connection established successfully!");
        }).exceptionally(e -> {
            System.err.println("[Reverb Error] WebSocket connection failed during handshake: " + e.getMessage());
            e.printStackTrace();
            return null;
        });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("[Reverb Debug] WebSocket connection opened. Listening for incoming events...");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("[Reverb Debug] WebSocket closed. Code: " + statusCode + " Reason: " + reason);
        if (!intentionalClose) {
            System.out.println("[Reverb Debug] Unexpected close, reconnecting in 5s...");
            scheduleReconnect();
        }
        return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            String completePayload = messageBuffer.toString();
            System.out.println("[Reverb Debug] Received complete text frame (Length: " + completePayload.length() + ")");
            handlePusherEvent(completePayload);
            messageBuffer.setLength(0);
        } else {
            System.out.println("[Reverb Debug] Received partial text frame, buffering...");
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[Reverb Error] WebSocket encountered an error: " + error.getMessage());
        error.printStackTrace();
        if (!intentionalClose) {
            scheduleReconnect();
        }
    }

    private void handlePusherEvent(String jsonString) {
        System.out.println("[Reverb RAW Payload] " + jsonString);
        try {
            JsonObject json = GSON.fromJson(jsonString, JsonObject.class);
            String event = json.has("event") ? json.get("event").getAsString() : "UNKNOWN";

            System.out.println("[Reverb Debug] Processing event type: " + event);

            switch (event) {
                case "pusher:connection_established" -> {
                    String dataStr = json.get("data").getAsString();
                    JsonObject dataObj = GSON.fromJson(dataStr, JsonObject.class);
                    String socketId = dataObj.get("socket_id").getAsString();

                    System.out.println("[Reverb Debug] Connection established. Extracted socket_id: " + socketId);
                    authenticateAndSubscribe(socketId);
                }
                case "pusher:ping" -> {
                    System.out.println("[Reverb Debug] Received ping from server. Sending pong response to keep connection alive...");
                    JsonObject pong = new JsonObject();
                    pong.addProperty("event", "pusher:pong");
                    webSocket.sendText(GSON.toJson(pong), true);
                }
                case "message.sent" -> {
                    System.out.println("[Reverb Debug] Received custom 'message.sent' event. Forwarding to listener.");
                    String dataStr = json.get("data").getAsString();
                    JsonObject messagePayload = GSON.fromJson(dataStr, JsonObject.class);
                    if (listener != null) {
                        listener.onNewMessage(messagePayload);
                    } else {
                        System.err.println("[Reverb Warning] Message received, but no listener is registered to handle it!");
                    }
                }
                case "presence.updated" -> {
                    String dataStr = json.get("data").getAsString();
                    JsonObject presenceData = GSON.fromJson(dataStr, JsonObject.class);
                    if (listener != null) {
                        listener.onPresenceUpdate(presenceData);
                    }
                }
                default -> System.out.println("[Reverb Debug] Ignored unhandled event type: '" + event + "'");
            }
        } catch (Exception e) {
            System.err.println("[Reverb Error] Failed to parse incoming WebSocket event: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void authenticateAndSubscribe(String socketId) {
        System.out.println("[Reverb Debug] Starting authentication process for channel: " + channelName);

        String token = Minecraft.getInstance().getUser().getAccessToken();
        if (token.isEmpty()) {
            System.err.println("[Reverb Error] Minecraft access token is null or empty. Authentication will likely fail.");
        }

        JsonObject authPayload = new JsonObject();
        authPayload.addProperty("socket_id", socketId);
        authPayload.addProperty("channel_name", channelName);

        String payloadString = GSON.toJson(authPayload);
        System.out.println("[Reverb Debug] Sending HTTP POST to Auth URL: " + AUTH_URL);
        System.out.println("[Reverb Debug] Auth Payload: " + payloadString);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AUTH_URL))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadString))
                .build();

        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            int status = response.statusCode();
            System.out.println("[Reverb Debug] Auth request completed. HTTP Status: " + status);

            if (status == 200) {
                JsonObject authResult = GSON.fromJson(response.body(), JsonObject.class);
                String authSignature = authResult.get("auth").getAsString();

                System.out.println("[Reverb Debug] Authentication successful. Signature received: " + authSignature);
                System.out.println("[Reverb Debug] Sending pusher:subscribe event for channel: " + channelName);

                JsonObject subscribeEvent = new JsonObject();
                subscribeEvent.addProperty("event", "pusher:subscribe");
                JsonObject data = new JsonObject();
                data.addProperty("channel", channelName);
                data.addProperty("auth", authSignature);
                subscribeEvent.add("data", data);

                webSocket.sendText(GSON.toJson(subscribeEvent), true);
                System.out.println("[Reverb Debug] Subscribe payload sent successfully.");
            } else {
                System.err.println("[Reverb Error] Authentication failed! Expected HTTP 200, got: " + status);
                System.err.println("[Reverb Error] Auth Response Body: " + response.body());
            }
        }).exceptionally(e -> {
            System.err.println("[Reverb Error] Exception occurred during HTTP Auth request: " + e.getMessage());
            e.printStackTrace();
            return null;
        });
    }

    private void scheduleReconnect() {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            System.out.println("[Reverb Debug] Attempting reconnect...");
            this.webSocket = null;
            messageBuffer.setLength(0);
            connect();
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void disconnect() {
        intentionalClose = true;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing");
        }
    }
}