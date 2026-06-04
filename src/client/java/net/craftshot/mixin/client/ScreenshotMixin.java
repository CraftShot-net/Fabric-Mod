package net.craftshot.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.io.File;
import java.util.function.Consumer;

@Mixin(Screenshot.class)
public class ScreenshotMixin {

    @ModifyVariable(method = "grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V", at = @At("HEAD"), argsOnly = true, name = "callback")
    private static Consumer<Component> wrapMessageConsumer(Consumer<Component> callback) {

        return (Component vanillaMessage) -> {
            callback.accept(vanillaMessage);

            try {
                File screenshotsDir = new File(Minecraft.getInstance().gameDirectory, "screenshots");
                if (!screenshotsDir.exists() || !screenshotsDir.isDirectory()) return;

                File[] files = screenshotsDir.listFiles((_, name) -> name.toLowerCase().endsWith(".png"));
                if (files == null || files.length == 0) return;

                File newest = files[0];
                for (File f : files) {
                    if (f.lastModified() > newest.lastModified()) {
                        newest = f;
                    }
                }

                String fixedPath = newest.getAbsolutePath().replace("\\", "/");

                MutableComponent clickActionPart = Component.translatable("craftshot.screenshot.clickHere").withStyle(style -> style.withColor(ChatFormatting.AQUA).withHoverEvent(new HoverEvent.ShowText(Component.translatable("craftshot.screenshot.hoverText").withStyle(ChatFormatting.YELLOW))).withClickEvent(new ClickEvent.RunCommand("/craftshot " + fixedPath)));

                MutableComponent fullMessage = Component.literal("§8[§6CraftShot§8] ").append(Component.translatable("craftshot.screenshot.message", clickActionPart));

                callback.accept(fullMessage);

            } catch (Exception e) {
                System.err.println("[CraftShot] Error processing screenshot: " + e.getMessage());
            }
        };
    }
}