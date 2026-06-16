package net.craftshot.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

public class CraftShotToast implements Toast {

    private static final long DISPLAY_TIME_MS = 5000L;

    private final String senderName;
    private final String messageExcerpt;
    private final Supplier<PlayerSkin> skinSupplier;
    private boolean hide = false;

    private CraftShotToast(String senderName, String messageExcerpt, Supplier<PlayerSkin> skinSupplier) {
        this.senderName = senderName;
        this.messageExcerpt = messageExcerpt;
        this.skinSupplier = skinSupplier;
    }

    public static void show(String senderName, String messageExcerpt, Supplier<PlayerSkin> skinSupplier) {
        Minecraft.getInstance().gui.toastManager().addToast(
                new CraftShotToast(senderName, messageExcerpt, skinSupplier)
        );
    }

    @Override
    public @NonNull Visibility getWantedVisibility() {
        return hide ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void update(@NonNull ToastManager manager, long fullyVisibleForMs) {
        if (fullyVisibleForMs >= DISPLAY_TIME_MS) {
            hide = true;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        int w = width();
        int h = height();

        graphics.fill(0, 0, w, h, 0xEE0D0D0D);

        graphics.fill(0, 0, w, 2, 0xFFFFAA00);

        graphics.fill(0, 0, 3, h, 0xFFFFAA00);

        int skinX = 8;
        int skinY = (h - 16) / 2;
        Identifier skinId = getFixedSkinTexture(skinSupplier);
        graphics.blit(RenderPipelines.GUI_TEXTURED, skinId, skinX, skinY, 8.0F, 8.0F, 16, 16, 8, 8, 64, 64);
        graphics.blit(RenderPipelines.GUI_TEXTURED, skinId, skinX, skinY, 40.0F, 8.0F, 16, 16, 8, 8, 64, 64);

        int textX = skinX + 16 + 5;
        int textY1 = 5;
        int textY2 = 5 + font.lineHeight + 2;

        graphics.text(font, senderName, textX, textY1, 0xFFFFAA00);

        String excerpt = messageExcerpt;
        int maxW = w - textX - 6;
        if (font.width(excerpt) > maxW) {
            while (!excerpt.isEmpty() && font.width(excerpt + "…") > maxW) {
                excerpt = excerpt.substring(0, excerpt.length() - 1);
            }
            excerpt += "…";
        }
        graphics.text(font, excerpt, textX, textY2, 0xFFCCCCCC);
    }

    @Override
    public int width() {
        return 200;
    }

    @Override
    public int height() {
        return 32;
    }

    private static Identifier getFixedSkinTexture(Supplier<PlayerSkin> supplier) {
        Identifier id = supplier.get().body().id();
        String path = id.getPath();
        if (!path.startsWith("textures/") && !path.startsWith("skins/")) {
            return Identifier.withDefaultNamespace("textures/" + path + ".png");
        }
        return id;
    }
}
