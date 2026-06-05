package net.craftshot.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;

public class CraftShotChatState {

    public static final List<Conversation> CONVERSATIONS = new ArrayList<>();
    public static boolean isDataLoaded = false;
    public static boolean isDirty = false;

    public static void ensureLoaded() {
        if (!isDataLoaded && CONVERSATIONS.isEmpty()) {
            CraftShotApiClient.fetchConversationsFresh().thenAccept(fetched -> Minecraft.getInstance().execute(() -> {
                CONVERSATIONS.clear();
                for (var dto : fetched) {
                    CONVERSATIONS.add(new Conversation(dto.id(), dto.otherUserId(), dto.name(), dto.isOnline(), dto.serverIp()));
                }
                isDataLoaded = true;
                isDirty = true;
            }));
        }
    }

    public static void ensureMessagesLoaded(Conversation conversation) {
        if (conversation.messagesLoaded) return;

        CraftShotApiClient.fetchMessagesWithCache(conversation.id).thenAccept(result -> Minecraft.getInstance().execute(() -> {
            List<ChatMessage> pendingLive = conversation.messages.stream().filter(m -> m.liveOnly).toList();

            conversation.messages.clear();
            for (var dto : result.messages()) {
                conversation.messages.add(new ChatMessage(dto.sender(), dto.content(), dto.attachmentUrl()));
            }

            for (ChatMessage live : pendingLive) {
                boolean alreadyIn = conversation.messages.stream()
                        .anyMatch(m -> m.sender != null && m.sender.equals(live.sender) && m.content.equals(live.content));
                if (!alreadyIn) {
                    conversation.messages.add(live);
                }
            }

            conversation.messagesLoaded = true;
            isDirty = true;
        }));
    }

    public static void handleLiveMessage(JsonObject messageData) {
        Minecraft.getInstance().execute(() -> {
            try {
                JsonObject msgObj = messageData.has("message") ? messageData.getAsJsonObject("message") : messageData;
                long convId = msgObj.get("conversation_id").getAsLong();
                String content = msgObj.has("content") && !msgObj.get("content").isJsonNull() ? msgObj.get("content").getAsString() : "";
                String senderName = msgObj.getAsJsonObject("sender").get("username").getAsString();

                String attachmentUrl = null;
                if (msgObj.has("attachments") && !msgObj.get("attachments").isJsonNull()) {
                    JsonArray attachments = msgObj.getAsJsonArray("attachments");
                    if (!attachments.isEmpty()) {
                        String path = attachments.get(0).getAsString();
                        attachmentUrl = path.startsWith("http") ? path : "https://craftshot.net/storage/" + path;
                    }
                }

                ChatMessage liveMsg = new ChatMessage(senderName, content, attachmentUrl);
                liveMsg.liveOnly = true;

                Conversation target = findById(convId);
                if (target != null) {
                    boolean isDuplicate = !target.messages.isEmpty()
                            && target.messages.getLast().sender != null
                            && target.messages.getLast().sender.equals(liveMsg.sender)
                            && target.messages.getLast().content.equals(liveMsg.content);

                    if (!isDuplicate) {
                        target.messages.add(liveMsg);
                        CONVERSATIONS.remove(target);
                        CONVERSATIONS.addFirst(target);
                        isDirty = true;
                    }
                } else {
                    CraftShotApiClient.fetchConversationsFresh().thenAccept(fetched -> Minecraft.getInstance().execute(() -> {
                        for (var dto : fetched) {
                            if (findById(dto.id()) == null) {
                                CONVERSATIONS.addFirst(new Conversation(dto.id(), dto.otherUserId(), dto.name(), dto.isOnline(), dto.serverIp()));
                            }
                        }
                        Conversation reFetchedTarget = findById(convId);
                        if (reFetchedTarget != null) {
                            reFetchedTarget.messages.add(liveMsg);
                        }
                        isDirty = true;
                    }));
                }
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }
        });
    }

    public static Conversation findById(long id) {
        for (Conversation c : CONVERSATIONS) {
            if (c.id == id) return c;
        }
        return null;
    }

    public static class Conversation {
        public long id;
        public String name;
        public boolean isOnline;
        public long otherUserId;
        public String serverIp;
        public List<ChatMessage> messages = new ArrayList<>();
        public boolean messagesLoaded = false;

        public Conversation(long id, long otherUserId, String name, boolean isOnline, String serverIp) {
            this.id = id;
            this.otherUserId = otherUserId;
            this.name = name;
            this.isOnline = isOnline;
            this.serverIp = serverIp;
        }
    }

    public static class ChatMessage {
        public String sender;
        public String content;
        public String attachmentUrl;
        public boolean failed = false;
        public boolean liveOnly = false;

        public ChatMessage(String sender, String content, String attachmentUrl) {
            this.sender = sender;
            this.content = content;
            this.attachmentUrl = attachmentUrl;
        }
    }
}