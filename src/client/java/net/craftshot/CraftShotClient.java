package net.craftshot;

import com.mojang.blaze3d.platform.InputConstants;
import net.craftshot.client.api.CraftShotApiClient;
import net.craftshot.client.gui.CraftShotDMScreen;
import net.craftshot.command.CraftShotCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CraftShotClient implements ClientModInitializer {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("craftshot", "keys"));

    private static final KeyMapping openDmsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.craftshot.open_dms", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));
    private ScheduledExecutorService heartbeatScheduler;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, _) -> CraftShotCommand.register(dispatcher));
        LiveScreenshotTask.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(_ -> {
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

            CraftShotApiClient.sendClientOffline();
        });

        ClientPlayConnectionEvents.JOIN.register((_, _, client) -> {
            ServerData currentServer = client.getCurrentServer();
            if (currentServer != null) {
                CraftShotApiClient.sendServerJoin(currentServer.ip);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((_, client) -> {
            ServerData currentServer = client.getCurrentServer();
            String serverIp = (currentServer != null) ? currentServer.ip : null;

            CraftShotApiClient.sendServerLeave(serverIp);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openDmsKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(CraftShotDMScreen.getInstance());
                }
            }
        });

    }
}