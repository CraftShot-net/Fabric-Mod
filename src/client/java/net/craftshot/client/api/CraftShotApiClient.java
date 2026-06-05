package net.craftshot.client.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CraftShotApiClient {

    private static final String BASE_URL = "https://craftshot.net/api/v2/messages";
    private static final String UUID_BASE_URL = "https://craftshot.net/api/minecraft-uuid/";
    private static final long CONVERSATION_CACHE_TTL_MS = 60_000;
    private static final long MESSAGE_CACHE_TTL_MS = 60_000;
    private static final HttpClient CLIENT = createHttpClient();
    private static final Gson GSON = new Gson();
    private static final Map<Long, CachedMessages> MESSAGE_CACHE = new ConcurrentHashMap<>();

    public static long myDatabaseId = -1;

    private record CachedConversations(List<ConversationDTO> conversations, long cachedAtMillis) {
    }

    private record CachedMessages(List<MessageDTO> messages, long cachedAtMillis) {
    }

    public static void clearCache() {
        CONVERSATION_CACHE = null;
        MESSAGE_CACHE.clear();
    }
    
    private static volatile CachedConversations CONVERSATION_CACHE;

    private static HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        return builder.build();
    }

    private static String getSessionToken() {
        return Minecraft.getInstance().getUser().getAccessToken();
    }

    public record ConversationDTO(long id, long otherUserId, String name, boolean isOnline, String serverIp) {}

    public record MessageDTO(long id, String sender, String content, String attachmentUrl) {
    }

    public record ConversationFetchResult(List<ConversationDTO> conversations, boolean fromCache) {
    }

    public record MessageFetchResult(List<MessageDTO> messages, boolean fromCache) {
    }

    public static CompletableFuture<ConversationFetchResult> fetchConversationsWithCache() {
        CachedConversations cached = CONVERSATION_CACHE;
        long now = System.currentTimeMillis();

        if (cached != null && (now - cached.cachedAtMillis) <= CONVERSATION_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(new ConversationFetchResult(List.copyOf(cached.conversations), true));
        }

        return fetchConversationsFresh().thenApply(conversations -> new ConversationFetchResult(conversations, false));
    }

    public static CompletableFuture<List<ConversationDTO>> fetchConversationsFresh() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL)).header("Authorization", "Bearer " + getSessionToken()).header("Accept", "application/json").GET().build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

                    if (json.has("my_id") && !json.get("my_id").isJsonNull()) {
                        myDatabaseId = json.get("my_id").getAsLong();
                    }
                    JsonArray convosArray = json.getAsJsonArray("conversations");
                    List<ConversationDTO> list = getConversationDTOS(convosArray);
                    CONVERSATION_CACHE = new CachedConversations(List.copyOf(list), System.currentTimeMillis());
                    return list;
                } else {
                    System.err.println("API Error fetchConversations - Status: " + response.statusCode());
                    System.err.println("API Response: " + response.body());
                }
            } catch (Exception e) {
                System.err.println("Error fetching conversations: " + e.getMessage());
            }
            return Collections.emptyList();
        });
    }

    public static void markConversationAsRead(long conversationId) {
        String token = Minecraft.getInstance().getUser().getAccessToken();

        String url = "https://craftshot.net/api/v2/messages/" + conversationId + "/read";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()) // Leerer Body
                .build();

        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200 || response.statusCode() == 204) {
                        return true;
                    } else {
                        System.err.println("[API Error] Failed to mark chat as read - Status: " + response.statusCode());
                        return false;
                    }
                })
                .exceptionally(e -> {
                    System.err.println("[API Error] Exception while marking chat as read: " + e.getMessage());
                    return false;
                });
    }

    private static @NonNull List<ConversationDTO> getConversationDTOS(JsonArray convosArray) {
        List<ConversationDTO> list = new ArrayList<>();

        for (JsonElement elem : convosArray) {

            if (!elem.isJsonObject()) {
                System.err.println("[API Error] Found non-object element in conversations array: " + elem);
                continue;
            }

            JsonObject convObj = elem.getAsJsonObject();
            long id = convObj.get("id").getAsLong();

            String name = "Unknown";
            boolean isOnline = false;
            String serverIp = null;
            long otherUserId = -1;

            if (convObj.has("other_users")) {
                JsonArray otherUsers = convObj.getAsJsonArray("other_users");
                if (!otherUsers.isEmpty()) {
                    JsonObject otherUser = otherUsers.get(0).getAsJsonObject();

                    if (otherUser.has("id") && !otherUser.get("id").isJsonNull()) {
                        otherUserId = otherUser.get("id").getAsLong(); // neu
                    }

                    if (otherUser.has("username") && !otherUser.get("username").isJsonNull()) {
                        name = otherUser.get("username").getAsString();
                    }

                    if (otherUser.has("is_online") && !otherUser.get("is_online").isJsonNull()) {
                        isOnline = otherUser.get("is_online").getAsBoolean();
                    }
                    if (otherUser.has("current_server") && !otherUser.get("current_server").isJsonNull()) {
                        serverIp = otherUser.get("current_server").getAsString();
                    }
                }
            }
            
            // If the conversation has a specific name, it overrides the user's name
            if (convObj.has("name") && !convObj.get("name").isJsonNull()) {
                name = convObj.get("name").getAsString();
            }

            list.add(new ConversationDTO(id, otherUserId, name, isOnline, serverIp));
        }
        return list;
    }

    public static CompletableFuture<MessageFetchResult> fetchMessagesWithCache(long conversationId) {
        CachedMessages cached = MESSAGE_CACHE.get(conversationId);
        long now = System.currentTimeMillis();

        if (cached != null && (now - cached.cachedAtMillis) <= MESSAGE_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(new MessageFetchResult(List.copyOf(cached.messages), true));
        }

        return fetchMessagesFresh(conversationId).thenApply(messages -> new MessageFetchResult(messages, false));
    }

    public static CompletableFuture<List<MessageDTO>> fetchMessagesFresh(long conversationId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/conversations/" + conversationId + "/messages")).header("Authorization", "Bearer " + getSessionToken()).header("Accept", "application/json").GET().build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    JsonArray messagesArray = json.getAsJsonArray("messages");
                    List<MessageDTO> list = parseMessages(messagesArray);
                    MESSAGE_CACHE.put(conversationId, new CachedMessages(List.copyOf(list), System.currentTimeMillis()));
                    return list;
                } else {
                    System.err.println("API Error fetchMessages - Status: " + response.statusCode());
                }
            } catch (Exception e) {
                System.err.println("Error fetching messages: " + e.getMessage());
            }
            return Collections.emptyList();
        });
    }

    private static List<MessageDTO> parseMessages(JsonArray messagesArray) {
        List<MessageDTO> list = new ArrayList<>();

        for (JsonElement elem : messagesArray) {
            JsonObject msgObj = elem.getAsJsonObject();

            if (isDeletedMessage(msgObj)) {
                continue;
            }

            long id = msgObj.get("id").getAsLong();

            String content = msgObj.has("content") && !msgObj.get("content").isJsonNull() ? msgObj.get("content").getAsString() : "";

            String senderName = "Unknown";
            if (msgObj.has("sender") && !msgObj.get("sender").isJsonNull()) {
                senderName = msgObj.getAsJsonObject("sender").get("username").getAsString();
            }

            String attachmentUrl = null;
            if (msgObj.has("attachments") && !msgObj.get("attachments").isJsonNull()) {
                JsonArray attachments = msgObj.getAsJsonArray("attachments");
                if (!attachments.isEmpty()) {
                    String path = attachments.get(0).getAsString();
                    attachmentUrl = path.startsWith("http") ? path : "https://craftshot.net/storage/" + path;
                }
            }

            list.add(new MessageDTO(id, senderName, content, attachmentUrl));
        }

        return list;
    }

    private static boolean isDeletedMessage(JsonObject msgObj) {
        return (msgObj.has("deleted_at") && !msgObj.get("deleted_at").isJsonNull()) || (msgObj.has("deleted") && !msgObj.get("deleted").isJsonNull() && msgObj.get("deleted").getAsBoolean()) || (msgObj.has("is_deleted") && !msgObj.get("is_deleted").isJsonNull() && msgObj.get("is_deleted").getAsBoolean());
    }

    /**
     * Returns null on success, or the server error message string on failure.
     */
    public static CompletableFuture<String> sendMessage(long conversationId, String content) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("content", content);

                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/" + conversationId)).header("Authorization", "Bearer " + getSessionToken()).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))).build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return null; // success
                }

                // Try to extract the "error" field from the JSON response
                try {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    if (json.has("error") && !json.get("error").isJsonNull()) {
                        return json.get("error").getAsString();
                    }
                } catch (Exception ignored) {
                }

                return "HTTP " + response.statusCode();

            } catch (Exception e) {
                System.err.println("Error sending message: " + e.getMessage());
                return e.getMessage();
            }
        });
    }

    public static void sendServerJoin(String serverIp) {
        CompletableFuture.runAsync(() -> {
            try {
                com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
                payload.addProperty("access_token", getSessionToken());
                payload.addProperty("server_ip", serverIp);

                HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://craftshot.net/v2/server/join")).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))).build();

                CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                System.err.println("Server join notice failed: " + e.getMessage());
            }
        });
    }

    public static void sendServerLeave(String serverIp) {
        CompletableFuture.runAsync(() -> {
            try {
                com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
                payload.addProperty("access_token", getSessionToken());
                if (serverIp != null) {
                    payload.addProperty("server_ip", serverIp);
                }

                HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://craftshot.net/v2/server/leave")).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))).build();

                CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                System.err.println("Server leave notice failed: " + e.getMessage());
            }
        });
    }

    public static void sendClientHeartbeat() {
        CompletableFuture.runAsync(() -> {
            try {
                com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
                payload.addProperty("access_token", getSessionToken());

                HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://craftshot.net/v2/client/heartbeat")).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))).build();

                CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                System.err.println("Client heartbeat failed: " + e.getMessage());
            }
        });
    }

    public static void sendClientOffline() {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("access_token", getSessionToken());

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://craftshot.net/v2/client/offline")).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))).build();

            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("Client offline notice failed: " + e.getMessage());
        }
    }

    public static CompletableFuture<UUID> fetchUuid(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = UUID_BASE_URL + username;

                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/json").GET().build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

                    if (json.has("id") && !json.get("id").isJsonNull()) {
                        String idStr = json.get("id").getAsString();
                        return parseUuid(idStr);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error fetching UUID for " + username + ": " + e.getMessage());
            }
            return null;
        });
    }

    private static UUID parseUuid(String uuidString) {
        if (uuidString.contains("-")) {
            return UUID.fromString(uuidString);
        }
        if (uuidString.length() == 32) {
            String formatted = uuidString.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            return UUID.fromString(formatted);
        }
        throw new IllegalArgumentException("Invalid UUID Format: " + uuidString);
    }
}