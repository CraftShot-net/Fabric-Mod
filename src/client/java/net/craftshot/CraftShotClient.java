package net.craftshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.craftshot.client.api.CraftShotApiClient;
import net.craftshot.client.api.CraftShotChatState;
import net.craftshot.client.api.ReverbClient;
import net.craftshot.client.gui.CraftShotDMScreen;
import net.craftshot.client.gui.CraftShotToast;
import net.craftshot.command.CraftShotCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CraftShotClient implements ClientModInitializer {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("craftshot", "keys"));

    private static final KeyMapping openDmsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.craftshot.open_dms", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));
    private ScheduledExecutorService heartbeatScheduler;
    private static ReverbClient reverbClient;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, _) -> CraftShotCommand.register(dispatcher));
        LiveScreenshotTask.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(_ -> {
            connectReverbIfNeeded();
            CraftShotApiClient.sendClientHeartbeat();

            this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CraftShot-Heartbeat");
                thread.setDaemon(true);
                return thread;
            });

            this.heartbeatScheduler.scheduleAtFixedRate(CraftShotApiClient::sendClientHeartbeat, 45, 45, TimeUnit.SECONDS);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(_ -> {
            if (this.heartbeatScheduler != null) {
                this.heartbeatScheduler.shutdown();
            }
            if (reverbClient != null) {
                reverbClient.disconnect();
                reverbClient = null;
            }

            Runtime.getRuntime().addShutdownHook(new Thread(CraftShotApiClient::sendClientOffline, "CraftShot-Offline"));
            CraftShotApiClient.sendClientOffline();
        });

        ClientPlayConnectionEvents.JOIN.register((_, _, client) -> {
            ServerData currentServer = client.getCurrentServer();
            if (currentServer != null) {
                CraftShotApiClient.sendServerJoin(currentServer.ip);
            }
            connectReverbIfNeeded();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((_, client) -> {
            ServerData currentServer = client.getCurrentServer();
            String serverIp = (currentServer != null) ? currentServer.ip : null;
            CraftShotApiClient.sendServerLeave(serverIp);

            //only clear when we actually left a server ant not switch from main menu to server
            if (serverIp != null) {
                CraftShotApiClient.clearCache();
                CraftShotDMScreen.clearInstance();
                CraftShotChatState.CONVERSATIONS.clear();
                CraftShotChatState.isDataLoaded = false;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openDmsKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(CraftShotDMScreen.getInstance());
                }
            }
        });
    }

    private static void connectReverbIfNeeded() {
        if (reverbClient != null) return;

        if (CraftShotApiClient.myDatabaseId != -1) {
            connectReverb();
        } else {
            CraftShotApiClient.fetchConversationsWithCache().thenAccept(_ ->
                    Minecraft.getInstance().execute(CraftShotClient::connectReverb));
        }
    }

    private static void connectReverb() {
        if (reverbClient == null && CraftShotApiClient.myDatabaseId != -1) {
            reverbClient = new ReverbClient(
                    String.valueOf(CraftShotApiClient.myDatabaseId),
                    new ReverbClient.MessageListener() {
                        public void onNewMessage(JsonObject data) {
                            handleIncomingMessage(data);
                        }
                        public void onPresenceUpdate(JsonObject data) {
                            long userId = data.get("user_id").getAsLong();
                            boolean isOnline = data.get("is_online").getAsBoolean();
                            String server = data.has("current_server") && !data.get("current_server").isJsonNull()
                                    ? data.get("current_server").getAsString() : null;

                            Minecraft.getInstance().execute(() -> {
                                CraftShotChatState.handlePresenceUpdate(userId, isOnline, server);
                            });
                        }
                    }
            );
            reverbClient.connect();
        }
    }

    private static void handleIncomingMessage(JsonObject messageData) {
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

            boolean dmScreenOpen = Minecraft.getInstance().gui.screen() instanceof CraftShotDMScreen;
            boolean isFocused = false;

            if (dmScreenOpen) {
                CraftShotDMScreen screen = (CraftShotDMScreen) Minecraft.getInstance().gui.screen();
                if (screen.getActiveConversationId() == convId) {
                    isFocused = true;
                    CraftShotApiClient.markConversationAsRead(convId);
                }
            }

            CraftShotChatState.handleLiveMessage(messageData, isFocused);
            CraftShotChatState.updateTaskbarBadge();

            if (!isFocused) {
                String toastText = !content.isEmpty() ? content : attachmentUrl != null ? Component.translatable("craftshot.dm.notification.image").getString() : "…";
                CraftShotToast.show(senderName, toastText, CraftShotDMScreen.getSkinAsyncPublic(senderName));
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 2.0F));
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }
}