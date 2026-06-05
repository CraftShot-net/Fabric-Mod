package net.craftshot.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.craftshot.client.gui.CraftShotDMScreen;
import net.craftshot.client.gui.CraftShotToast;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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

    public static void resetUnread(long convId) {
        Conversation c = findById(convId);
        if (c != null && c.unreadCount > 0) {
            c.unreadCount = 0;
            updateTaskbarBadge();
        }
    }

    public static void updateTaskbarBadge() {
        int totalUnread = 0;
        for (Conversation c : CONVERSATIONS) {
            totalUnread += c.unreadCount;
        }

        final int finalCount = totalUnread;

        CompletableFuture.runAsync(() -> {
            try {
                if (java.awt.Taskbar.isTaskbarSupported()) {
                    java.awt.Taskbar taskbar = java.awt.Taskbar.getTaskbar();
                    if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_BADGE_TEXT)) {
                        taskbar.setIconBadge(finalCount > 0 ? String.valueOf(finalCount) : null);
                    }
                }
            } catch (Throwable t) {
            }
        });
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

    public static void handleLiveMessage(JsonObject messageData, boolean isFocused) {
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
                        if (!isFocused) target.unreadCount++;
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
                            if (!isFocused) reFetchedTarget.unreadCount++;
                        }
                        isDirty = true;
                    }));
                }
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }
        });
    }

    public static void handlePresenceUpdate(long userId, boolean isOnline, String serverIp) {
        Conversation target = null;
        for (Conversation conv : CONVERSATIONS) {
            if (conv.otherUserId == userId) {
                target = conv;
                break;
            }
        }

        if (target != null) {
            boolean wasOnline = target.isOnline;
            String oldServer = target.serverIp;

            if (wasOnline == isOnline && (Objects.equals(serverIp, oldServer))) {
                return;
            }

            target.isOnline = isOnline;
            target.serverIp = serverIp;
            isDirty = true;

            if (!wasOnline && isOnline) {
                CraftShotToast.show(target.name, "Online", CraftShotDMScreen.getSkinAsyncPublic(target.name));
            } else if (wasOnline && !isOnline) {
                CraftShotToast.show(target.name, "Offline", CraftShotDMScreen.getSkinAsyncPublic(target.name));
            } else if (isOnline && serverIp != null) {
                CraftShotToast.show(target.name, "🎮 " + serverIp, CraftShotDMScreen.getSkinAsyncPublic(target.name));
            }
        }
    }

    public static int getTotalUnread() {
        int total = 0;
        for (Conversation c : CONVERSATIONS) {
            total += c.unreadCount;
        }
        return total;
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
        public int unreadCount = 0;

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