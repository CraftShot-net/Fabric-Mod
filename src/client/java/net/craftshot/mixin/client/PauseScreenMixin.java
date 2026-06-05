package net.craftshot.mixin.client;

import net.craftshot.client.api.CraftShotChatState;
import net.craftshot.client.gui.CraftShotDMScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void addCraftShotButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(Component.literal("DMs"), button -> {
            Minecraft.getInstance().setScreen(CraftShotDMScreen.getInstance());
        }).bounds(10, this.height - 30, 50, 20).build());
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void renderUnreadBadge(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        int unread = CraftShotChatState.getTotalUnread();
        if (unread > 0) {
            int badgeY = this.height - 35;
            graphics.fill(50, badgeY, 65, badgeY + 15, 0xFFFF0000);
            graphics.centeredText(this.font, String.valueOf(unread), 57, badgeY + 4, 0xFFFFFFFF);
        }
    }
}