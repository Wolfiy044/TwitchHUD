package com.wolfiy.twitchhud.fabric.mixin;

import com.wolfiy.twitchhud.fabric.TwitchHudFabric;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class InGameHudMixin {
    @Shadow
    @Final
    private GuiRenderState guiRenderState;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void twitchhud$onRender(DeltaTracker tracker, boolean isLevelLoaded, boolean isBossHudLayer, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        GuiGraphicsExtractor extractor = new GuiGraphicsExtractor(
                client,
                guiRenderState,
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight()
        );
        TwitchHudFabric.onHudRender(extractor);
    }
}
