package net.craftshot.mixin.client;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Mixin(TitleScreen.class)
public abstract class TitleScreenUpdateMixin extends Screen {

    @Unique
    private static final Identifier LOGO = Identifier.parse("craftshot:textures/logo.png");

    @Unique
    private static final String API_URL = "https://api.modrinth.com/v2/project/craftshot/version";

    @Unique
    private static final int PAD = 10;
    @Unique
    private static final int ACCENT = 4;
    @Unique
    private static final int AX = 8, AY = 8;

    @Unique
    private static volatile boolean checkStarted = false;
    @Unique
    private static volatile boolean updateAvailable = false;
    @Unique
    private static volatile String latestVersion = null;

    @Unique
    private long appearTime = -1;
    @Unique
    private double cursorX, cursorY;

    protected TitleScreenUpdateMixin(Component title) {
        super(title);
    }

    @Inject(method = "registerTextures", at = @At("TAIL"))
    private static void registerLogo(TextureManager textureManager, CallbackInfo ci) {
        textureManager.registerForNextReload(LOGO);
    }

    @Unique
    private static void startUpdateCheck() {
        if (checkStarted) return;
        checkStarted = true;

        Thread.ofVirtual().start(() -> {
            try {
                String current = FabricLoader.getInstance().getModContainer("craftshot").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");

                HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(URI.create(API_URL)).header("User-Agent", "craftshot-mod/update-checker").GET().build(), HttpResponse.BodyHandlers.ofString());

                var versions = JsonParser.parseString(res.body()).getAsJsonArray();
                if (!versions.isEmpty()) {
                    String newest = versions.get(0).getAsJsonObject().get("version_number").getAsString();
                    if (!newest.equals(current)) {
                        latestVersion = newest;
                        updateAvailable = true;
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Unique
    private String line1() {
        return Component.translatable("craftshot.update.available", latestVersion).getString();
    }

    @Unique
    private static String line2() {
        return Component.translatable("craftshot.update.download").getString();
    }

    @Unique
    private int badgeW() {
        return Math.max(this.font.width(line1()), this.font.width(line2()))
                + ACCENT + PAD + logoSize() + 4 + PAD;
    }

    @Unique
    private int badgeH() {
        return Math.max(logoSize(), this.font.lineHeight * 2 + 6) + PAD * 2;
    }

    @Unique
    private int logoSize() {
        return this.font.lineHeight * 2 + 6;
    }

    @Unique
    private boolean hovered() {
        return cursorX >= AX && cursorX <= AX + badgeW() && cursorY >= AY && cursorY <= AY + badgeH();
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void renderBadge(GuiGraphicsExtractor g, int mx, int my, float a, CallbackInfo ci) {
        startUpdateCheck();
        cursorX = mx;
        cursorY = my;

        if (!updateAvailable || latestVersion == null) return;

        if (appearTime < 0) appearTime = Util.getMillis();
        long now = Util.getMillis();

        float t = Math.min(1f, (now - appearTime) / 350f);
        float ease = 1f - (1f - t) * (1f - t);
        int offX = (int) (-(badgeW() + AX) * (1f - ease));

        int x = AX + offX, y = AY, w = badgeW(), h = badgeH();
        boolean hov = hovered();
        float pulse = (float) (Math.sin(now / 500.0) * 0.5 + 0.5);

        int bgA = hov ? 220 : 175;
        g.fillGradient(x, y, x + w, y + h, ARGB.color(bgA, 12, 12, 18), ARGB.color(bgA, 24, 18, 30));

        if (hov) g.fillGradient(x, y, x + w, y + 1, ARGB.color(130, 255, 210, 80), ARGB.color(0, 255, 210, 80));

        int accentA = hov ? 255 : (int) (155 + pulse * 85);
        int accentG = (int) (120 + pulse * 80);
        g.fillGradient(x, y, x + ACCENT, y + h, ARGB.color(accentA, 255, accentG, 0), ARGB.color(accentA / 2, 180, 60, 0));

        int lx = x + ACCENT + PAD;
        int ls = logoSize();
        int ly = y + (h - ls) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, lx, ly, 0, 0, ls, ls, ls, ls);
        int tx = lx + ls + 4;
        int ty = y + (h - this.font.lineHeight * 2 - 3) / 2;
        g.text(this.font, line1(), tx, ty, hov ? ARGB.color(255, 255, 225, 80) : ARGB.color(255, 220, 175, 35));
        g.text(this.font, line2(), tx, ty + this.font.lineHeight + 3, ARGB.color(hov ? 210 : 150, 200, 200, 200));
    }


    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onClick(MouseButtonEvent event, boolean dc, CallbackInfoReturnable<Boolean> cir) {
        if (!updateAvailable || latestVersion == null) return;
        if (event.button() == 0 && event.x() >= AX && event.x() <= AX + badgeW() && event.y() >= AY && event.y() <= AY + badgeH()) {
            Util.getPlatform().openUri("https://modrinth.com/mod/craftshot");
            cir.setReturnValue(true);
        }
    }
}