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

    public interface MessageListener {
        void onNewMessage(JsonObject messageData);
    }

    public ReverbClient(String databaseId, MessageListener listener) {
        this.listener = listener;
        this.channelName = "private-user." + databaseId;
    }

    public void connect() {
        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(REVERB_WS_URL), this)
                .thenAccept(ws -> this.webSocket = ws)
                .exceptionally(_ -> null);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            handlePusherEvent(messageBuffer.toString());
            messageBuffer.setLength(0);
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    private void handlePusherEvent(String jsonString) {

        System.out.println("[Reverb RAW] " + jsonString);

        try {
            JsonObject json = GSON.fromJson(jsonString, JsonObject.class);
            String event = json.get("event").getAsString();

            if (event.equals("pusher:connection_established")) {
                String dataStr = json.get("data").getAsString();
                JsonObject dataObj = GSON.fromJson(dataStr, JsonObject.class);
                String socketId = dataObj.get("socket_id").getAsString();

                authenticateAndSubscribe(socketId);
            }
            else if (event.equals("message.sent")) {
                String dataStr = json.get("data").getAsString();
                JsonObject messagePayload = GSON.fromJson(dataStr, JsonObject.class);
                listener.onNewMessage(messagePayload);
            }
        } catch (Exception ignored) {}
    }

    private void authenticateAndSubscribe(String socketId) {
        String token = Minecraft.getInstance().getUser().getAccessToken();

        JsonObject authPayload = new JsonObject();
        authPayload.addProperty("socket_id", socketId);
        authPayload.addProperty("channel_name", channelName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AUTH_URL))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(authPayload)))
                .build();

        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject authResult = GSON.fromJson(response.body(), JsonObject.class);
                        String authSignature = authResult.get("auth").getAsString();

                        JsonObject subscribeEvent = new JsonObject();
                        subscribeEvent.addProperty("event", "pusher:subscribe");
                        JsonObject data = new JsonObject();
                        data.addProperty("channel", channelName);
                        data.addProperty("auth", authSignature);
                        subscribeEvent.add("data", data);

                        webSocket.sendText(GSON.toJson(subscribeEvent), true);
                        System.out.println("[Reverb] Successfully subscribed to " + channelName);
                    } else {
                        System.err.println("[Reverb] Auth failed - Status: " + response.statusCode());
                        System.err.println("[Reverb] Auth Response: " + response.body());
                    }
                });
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing UI");
        }
    }
}