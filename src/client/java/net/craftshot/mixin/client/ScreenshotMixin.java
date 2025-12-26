package net.craftshot.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.network.chat.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;


@Mixin(Screenshot.class)
public class ScreenshotMixin {

    private static File capturedGameDirectory = null;

    @Inject(method = "grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V", at = @At("HEAD"))
    private static void onGrabStart(File gameDirectory, RenderTarget buffer, Consumer<Component> messageConsumer, CallbackInfo ci) {
        capturedGameDirectory = gameDirectory;
    }

    @Inject(method = "grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V", at = @At("RETURN"))
    private static void onGrabEnd(File gameDirectory, RenderTarget buffer, Consumer<Component> messageConsumer, CallbackInfo ci) {
        if (capturedGameDirectory == null) return;

        if (Minecraft.getInstance().player == null) return;

        try {
            File screenshotsDir = new File(capturedGameDirectory, "screenshots");
            if (!screenshotsDir.exists() || !screenshotsDir.isDirectory()) return;

            // Get files
            File[] files = screenshotsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
            if (files == null || files.length == 0) return;

            // Find newest file safely
            File newest = files[0];
            for (File f : files) {
                if (f.lastModified() > newest.lastModified()) {
                    newest = f;
                }
            }

            final File screenshotFile = newest;
            String fixedPath = screenshotFile.getAbsolutePath().replace("\\", "/");

            MutableComponent clickActionPart = Component.translatable("craftshot.screenshot.clickHere").withStyle(style -> style.withColor(ChatFormatting.AQUA).withHoverEvent(new HoverEvent.ShowText(Component.translatable("craftshot.screenshot.hoverText").withStyle(ChatFormatting.YELLOW))).withClickEvent(new ClickEvent.RunCommand("/craftshot " + fixedPath)));

            MutableComponent fullMessage = Component.literal("§8[§6CraftShot§8] ").append(Component.translatable("craftshot.screenshot.message", clickActionPart));

            Minecraft.getInstance().player.displayClientMessage(fullMessage, false);

        } catch (Exception e) {
            System.err.println("[CraftShot] Error processing screenshot: " + e.getMessage());
        } finally {
            capturedGameDirectory = null;
        }
    }
}