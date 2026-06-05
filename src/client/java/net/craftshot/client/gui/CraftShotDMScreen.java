package net.craftshot.client.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.blaze3d.platform.NativeImage;
import net.craftshot.client.api.CraftShotApiClient;
import net.craftshot.client.api.CraftShotChatState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class CraftShotDMScreen extends Screen {

    private static CraftShotDMScreen INSTANCE;

    public static CraftShotDMScreen getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CraftShotDMScreen();
        }
        return INSTANCE;
    }

    public static void clearInstance() {
        INSTANCE = null;
    }

    private EditBox messageField;
    private int activeConvIndex = 0;
    private long currentActiveId = -1;
    private int sidebarScrollY = 0;
    private int chatScrollY = 0;

    private static Map<String, Supplier<PlayerSkin>> SKIN_CACHE;
    private final Map<String, Identifier> imageCache = new HashMap<>();
    private final List<String> downloadingImages = new ArrayList<>();

    private CraftShotDMScreen() {
        super(Component.translatable("craftshot.dm.title"));
    }

    private int getSidebarWidth() {
        return Math.clamp(this.width / 3, 95, 140);
    }

    @Override
    protected void init() {
        super.init();

        int sidebarWidth = getSidebarWidth();
        int chatWidth = this.width - sidebarWidth;
        int fieldWidth = Math.max(30, chatWidth - 70);

        this.messageField = new EditBox(this.font, sidebarWidth + 10, this.height - 30, fieldWidth, 20, Component.translatable("craftshot.dm.messagePlaceholder"));
        this.messageField.setMaxLength(256);
        this.messageField.setEditable(!CraftShotChatState.CONVERSATIONS.isEmpty());
        this.addRenderableWidget(this.messageField);

        Button sendButton = Button.builder(Component.translatable("craftshot.dm.send"), _ -> sendMessage()).bounds(this.width - 55, this.height - 30, 50, 20).build();
        sendButton.active = !CraftShotChatState.CONVERSATIONS.isEmpty();
        this.addRenderableWidget(sendButton);

        CraftShotChatState.ensureLoaded();

        if (!CraftShotChatState.CONVERSATIONS.isEmpty()) {
            activeConvIndex = Math.min(activeConvIndex, CraftShotChatState.CONVERSATIONS.size() - 1);
            CraftShotChatState.Conversation active = CraftShotChatState.CONVERSATIONS.get(activeConvIndex);
            currentActiveId = active.id;
            CraftShotApiClient.markConversationAsRead(active.id);
            CraftShotChatState.resetUnread(active.id);
            CraftShotChatState.ensureMessagesLoaded(active);
        }
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    public long getActiveConversationId() {
        return currentActiveId;
    }

    public String getUsernameByOtherUserId(long userId) {
        for (CraftShotChatState.Conversation conv : CraftShotChatState.CONVERSATIONS) {
            if (conv.otherUserId == userId) return conv.name;
        }
        return null;
    }

    public boolean isUserOnline(long userId) {
        for (CraftShotChatState.Conversation conv : CraftShotChatState.CONVERSATIONS) {
            if (conv.otherUserId == userId) return conv.isOnline;
        }
        return false;
    }

    public String getServerByOtherUserId(long userId) {
        for (CraftShotChatState.Conversation conv : CraftShotChatState.CONVERSATIONS) {
            if (conv.otherUserId == userId) return conv.serverIp;
        }
        return null;
    }

    public void handlePresenceUpdate(long userId, boolean isOnline, String serverIp) {
        for (CraftShotChatState.Conversation conv : CraftShotChatState.CONVERSATIONS) {
            if (conv.otherUserId == userId) {
                conv.isOnline = isOnline;
                conv.serverIp = serverIp;
                break;
            }
        }
    }

    private void sendMessage() {
        String text = this.messageField.getValue().trim();
        if (text.isEmpty() || CraftShotChatState.CONVERSATIONS.isEmpty()) return;

        activeConvIndex = Math.min(activeConvIndex, CraftShotChatState.CONVERSATIONS.size() - 1);
        CraftShotChatState.Conversation active = CraftShotChatState.CONVERSATIONS.get(activeConvIndex);

        this.messageField.setValue("");
        chatScrollY = 0;

        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.5F));

        String myUsername = Minecraft.getInstance().getUser().getName();
        CraftShotChatState.ChatMessage optimistic = new CraftShotChatState.ChatMessage(myUsername, text, null);
        active.messages.add(optimistic);

        CraftShotChatState.CONVERSATIONS.remove(active);
        CraftShotChatState.CONVERSATIONS.addFirst(active);
        activeConvIndex = 0;

        CraftShotApiClient.sendMessage(active.id, text).thenAccept(errorMsg -> Minecraft.getInstance().execute(() -> {
            if (errorMsg != null) {
                int idx = active.messages.indexOf(optimistic);
                if (idx >= 0) {
                    active.messages.get(idx).failed = true;
                    active.messages.add(idx + 1, new CraftShotChatState.ChatMessage(null, "§c✗ " + errorMsg, null));
                }
            }
        }));
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.messageField.isFocused()) {
                sendMessage();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        int sidebarWidth = getSidebarWidth();

        if (event.x() < sidebarWidth && event.y() > 30) {
            int startY = 30 + sidebarScrollY;
            int clickedIndex = (int) ((event.y() - startY) / 25);

            if (clickedIndex >= 0 && clickedIndex < CraftShotChatState.CONVERSATIONS.size()) {
                activeConvIndex = clickedIndex;
                CraftShotChatState.Conversation active = CraftShotChatState.CONVERSATIONS.get(activeConvIndex);
                currentActiveId = active.id;
                chatScrollY = 0;
                CraftShotApiClient.markConversationAsRead(active.id);
                CraftShotChatState.resetUnread(active.id);
                CraftShotChatState.ensureMessagesLoaded(active);
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        int sidebarWidth = getSidebarWidth();

        if (x < sidebarWidth) {
            int totalContentHeight = CraftShotChatState.CONVERSATIONS.size() * 25;
            int visibleHeight = this.height - 50;

            if (totalContentHeight > visibleHeight) {
                sidebarScrollY += (int) (scrollY * 15);
                sidebarScrollY = Math.min(0, sidebarScrollY);
                sidebarScrollY = Math.max(-(totalContentHeight - visibleHeight), sidebarScrollY);
            }
        } else if (!CraftShotChatState.CONVERSATIONS.isEmpty()) {
            activeConvIndex = Math.min(activeConvIndex, CraftShotChatState.CONVERSATIONS.size() - 1);
            CraftShotChatState.Conversation active = CraftShotChatState.CONVERSATIONS.get(activeConvIndex);
            int totalHeight = computeEstimatedChatHeight(active);
            int visibleHeight = this.height - 70;
            if (totalHeight > visibleHeight) {
                chatScrollY += (int) (scrollY * 15);
                chatScrollY = Math.max(0, chatScrollY);
                chatScrollY = Math.min(totalHeight - visibleHeight, chatScrollY);
            }
        }
        return true;
    }

    @Override
    public void extractRenderState(final @NonNull GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int sidebarWidth = getSidebarWidth();

        graphics.fill(0, 0, sidebarWidth, this.height, 0x80000000);
        graphics.verticalLine(sidebarWidth, 20, this.height - 20, 0xFFAAAAAA);

        drawSidebar(graphics, mouseX, mouseY, sidebarWidth);

        if (!CraftShotChatState.CONVERSATIONS.isEmpty()) {
            drawChatArea(graphics, sidebarWidth);
        } else {
            drawEmptyState(graphics, sidebarWidth);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (CraftShotChatState.isDirty) {
            if (!CraftShotChatState.CONVERSATIONS.isEmpty()) {
                if (currentActiveId != -1) {
                    for (int i = 0; i < CraftShotChatState.CONVERSATIONS.size(); i++) {
                        if (CraftShotChatState.CONVERSATIONS.get(i).id == currentActiveId) {
                            activeConvIndex = i;
                            break;
                        }
                    }
                }

                activeConvIndex = Math.min(activeConvIndex, CraftShotChatState.CONVERSATIONS.size() - 1);
                CraftShotChatState.Conversation active = CraftShotChatState.CONVERSATIONS.get(activeConvIndex);
                currentActiveId = active.id;
                CraftShotChatState.ensureMessagesLoaded(active);
            }

            if (this.messageField != null) {
                this.messageField.setEditable(!CraftShotChatState.CONVERSATIONS.isEmpty());
            }
            CraftShotChatState.isDirty = false;
        }
    }

    private void drawEmptyState(final GuiGraphicsExtractor graphics, int sidebarWidth) {
        int centerX = sidebarWidth + (this.width - sidebarWidth) / 2;
        int centerY = this.height / 2;

        String line1 = Component.translatable("craftshot.dm.empty.title").getString();
        String line2 = Component.translatable("craftshot.dm.empty.hint").getString();

        graphics.centeredText(this.font, line1, centerX, centerY - 10, 0xFFAAAAAA);
        graphics.centeredText(this.font, line2, centerX, centerY + 4, 0xFF666666);
    }

    private void drawSidebar(final GuiGraphicsExtractor graphics, int mouseX, int mouseY, int sidebarWidth) {
        graphics.centeredText(this.font, Component.translatable("craftshot.dm.chats").getString(), sidebarWidth / 2, 10, 0xFFFFFFFF);

        int startY = 30 + sidebarScrollY;
        int itemHeight = 25;

        graphics.enableScissor(0, 25, sidebarWidth, this.height - 20);

        for (int i = 0; i < CraftShotChatState.CONVERSATIONS.size(); i++) {
            CraftShotChatState.Conversation conv = CraftShotChatState.CONVERSATIONS.get(i);
            int convY = startY + (i * itemHeight);

            if (convY > this.height - 20 || convY + itemHeight < 25) continue;
            if (i == activeConvIndex) {
                graphics.fill(5, convY, sidebarWidth - 5, convY + itemHeight - 2, 0x80FFFFFF);
            } else if (mouseX >= 5 && mouseX < sidebarWidth - 5 && mouseY >= convY && mouseY < convY + itemHeight - 2) {
                graphics.fill(5, convY, sidebarWidth - 5, convY + itemHeight - 2, 0x40FFFFFF);
            }

            Identifier skinTexture = getFixedSkinTexture(getSkinAsync(conv.name));
            graphics.blit(RenderPipelines.GUI_TEXTURED, skinTexture, 10, convY + 4, 8.0F, 8.0F, 16, 16, 8, 8, 64, 64);
            graphics.blit(RenderPipelines.GUI_TEXTURED, skinTexture, 10, convY + 4, 40.0F, 8.0F, 16, 16, 8, 8, 64, 64);

            if (conv.isOnline) {
                graphics.fill(10 + 12, convY + 4 + 12, 10 + 16, convY + 4 + 16, 0xFF55FF55);
            }

            int maxNameWidth = sidebarWidth - 37;
            String displayString = this.font.plainSubstrByWidth(conv.name, maxNameWidth);

            int color = (i == activeConvIndex) ? 0xFFFFFF55 : 0xFFAAAAAA;
            graphics.text(this.font, displayString, 32, convY + 8, color);
        }

        graphics.disableScissor();
    }

    private void drawChatArea(final GuiGraphicsExtractor graphics, int sidebarWidth) {
        activeConvIndex = Math.min(activeConvIndex, CraftShotChatState.CONVERSATIONS.size() - 1);
        CraftShotChatState.Conversation active = CraftShotChatState.CONVERSATIONS.get(activeConvIndex);

        graphics.text(this.font, Component.translatable("craftshot.dm.chatWith", "§e" + active.name).getString(), sidebarWidth + 10, 8, 0xFFFFFFFF);

        String status = active.isOnline ? (active.serverIp != null && !active.serverIp.isEmpty() ? "§aOnline : " + active.serverIp : "§aOnline") : "§cOffline";
        graphics.text(this.font, status, sidebarWidth + 10, 20, 0xFFFFFFFF);

        graphics.horizontalLine(sidebarWidth + 10, this.width - 10, 32, 0xFFAAAAAA);

        int chatAreaBottom = this.height - 40 + chatScrollY;
        int maxTextWidth = Math.max(20, this.width - sidebarWidth - 35);

        graphics.enableScissor(sidebarWidth, 33, this.width, this.height - 40);

        for (int i = active.messages.size() - 1; i >= 0; i--) {
            CraftShotChatState.ChatMessage msg = active.messages.get(i);

            if (msg.sender == null) {
                int lineH = 9;
                int msgY = chatAreaBottom - lineH;
                if (msgY >= -20 && msgY <= this.height) {
                    graphics.text(this.font, msg.content, sidebarWidth + 10, msgY + 1, 0xFFFF5555);
                }
                chatAreaBottom -= (lineH + 3);
                continue;
            }

            String formattedMessage = msg.failed ? "§7<§f" + msg.sender + "§7> §8" + msg.content : "§7<§f" + msg.sender + "§7> §f" + msg.content;
            var lines = this.font.split(Component.literal(formattedMessage), maxTextWidth);

            int textHeight = Math.max(lines.size() * 9, 9);
            int imageSize = (msg.attachmentUrl != null) ? 60 : 0;
            int padding = (imageSize > 0) ? 5 : 0;
            int totalMsgHeight = textHeight + imageSize + padding;
            int msgY = chatAreaBottom - totalMsgHeight;

            if (msgY > this.height || msgY + totalMsgHeight < 0) {
                chatAreaBottom -= (totalMsgHeight + 5);
                continue;
            }

            Identifier skinTexture = getFixedSkinTexture(getSkinAsync(msg.sender));

            graphics.blit(RenderPipelines.GUI_TEXTURED, skinTexture, sidebarWidth + 10, msgY, 8.0F, 8.0F, 8, 8, 8, 8, 64, 64);
            graphics.blit(RenderPipelines.GUI_TEXTURED, skinTexture, sidebarWidth + 10, msgY, 40.0F, 8.0F, 8, 8, 8, 8, 64, 64);

            graphics.textWithWordWrap(this.font, Component.literal(formattedMessage), sidebarWidth + 22, msgY, maxTextWidth, 0xFFFFFFFF);

            if (msg.attachmentUrl != null) {
                Identifier attachmentImg = getOrLoadImage(msg.attachmentUrl);
                if (attachmentImg != null) {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, attachmentImg, sidebarWidth + 22, msgY + textHeight + 2, 0, 0, imageSize, imageSize, imageSize, imageSize);
                }
            }

            chatAreaBottom -= (totalMsgHeight + 5);
        }

        graphics.disableScissor();
    }

    private int computeEstimatedChatHeight(CraftShotChatState.Conversation conversation) {
        int total = 0;
        int maxTextWidth = Math.max(20, this.width - getSidebarWidth() - 35);

        for (CraftShotChatState.ChatMessage msg : conversation.messages) {
            if (msg.sender == null) {
                total += 9 + 3;
                continue;
            }
            String formatted = "§7<§f" + msg.sender + "§7> §f" + msg.content;
            int lineCount = Math.max(1, this.font.split(Component.literal(formatted), maxTextWidth).size());
            int textHeight = Math.max(lineCount * 9, 9);
            int imageSize = (msg.attachmentUrl != null) ? 60 : 0;
            int padding = (imageSize > 0) ? 5 : 0;
            total += textHeight + imageSize + padding + 5;
        }
        return total;
    }

    private Identifier getOrLoadImage(String url) {
        if (url == null || url.isEmpty()) return null;
        if (imageCache.containsKey(url)) return imageCache.get(url);
        if (downloadingImages.contains(url)) return null;

        downloadingImages.add(url);
        String hash = UUID.nameUUIDFromBytes(url.getBytes()).toString().replace("-", "");
        Identifier id = Identifier.withDefaultNamespace("craftshot_img_" + hash);

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() == 200) {
                    byte[] bytes = response.body();
                    try (ByteArrayInputStream is = new ByteArrayInputStream(bytes)) {
                        NativeImage nativeImage = NativeImage.read(is);
                        Minecraft.getInstance().execute(() -> {
                            DynamicTexture texture = new DynamicTexture(() -> "craftshot_img_" + hash, nativeImage);
                            Minecraft.getInstance().getTextureManager().register(id, texture);
                            imageCache.put(url, id);
                            downloadingImages.remove(url);
                        });
                    } catch (Exception ignored) {
                        imageCache.put(url, null);
                        downloadingImages.remove(url);
                    }
                } else {
                    imageCache.put(url, null);
                    downloadingImages.remove(url);
                }
            } catch (Exception ignored) {
                imageCache.put(url, null);
                downloadingImages.remove(url);
            }
        });
        return null;
    }

    private Identifier getFixedSkinTexture(Supplier<PlayerSkin> skinSupplier) {
        Identifier skinId = skinSupplier.get().body().id();
        String path = skinId.getPath();
        if (!path.startsWith("textures/") && !path.startsWith("skins/")) {
            return Identifier.withDefaultNamespace("textures/" + path + ".png");
        }
        return skinId;
    }

    public static Supplier<PlayerSkin> getSkinAsyncPublic(String username) {
        return getSkinAsync(username);
    }

    private static Supplier<PlayerSkin> getSkinAsync(String username) {
        if (SKIN_CACHE == null) SKIN_CACHE = new HashMap<>();

        return SKIN_CACHE.computeIfAbsent(username, name -> {
            class AsyncSkinSupplier implements Supplier<PlayerSkin> {
                private Supplier<PlayerSkin> currentSupplier;

                public AsyncSkinSupplier() {
                    UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
                    this.currentSupplier = Minecraft.getInstance().getSkinManager().createLookup(new GameProfile(offlineUUID, name), false);

                    CompletableFuture.runAsync(() -> {
                        try {
                            UUID realUUID = CraftShotApiClient.fetchUuid(name).get();
                            if (realUUID == null) return;
                            ProfileResult result = Minecraft.getInstance().services().sessionService().fetchProfile(realUUID, true);
                            if (result != null) {
                                Minecraft.getInstance().execute(() -> this.currentSupplier = Minecraft.getInstance().getSkinManager().createLookup(result.profile(), false));
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }

                @Override
                public PlayerSkin get() {
                    return currentSupplier.get();
                }
            }
            return new AsyncSkinSupplier();
        });
    }
}