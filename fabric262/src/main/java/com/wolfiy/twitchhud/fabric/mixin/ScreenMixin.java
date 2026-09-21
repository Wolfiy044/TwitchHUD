package com.wolfiy.twitchhud.fabric.mixin;

import com.wolfiy.twitchhud.fabric.TwitchHudFabric;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void twitchhud$afterRender(
            GuiGraphicsExtractor extractor,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        TwitchHudFabric.onScreenRender(extractor);
    }
}
