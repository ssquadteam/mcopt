package mcopt.metal.mixin.render;

import mcopt.metal.render.TextCache;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FontManager.class)
abstract class TextReloadMixin {
	@Inject(method = {"apply", "updateOptions"}, at = @At("TAIL"))
	private void mcopt$invalidate(CallbackInfo ci) {
		TextCache.invalidate();
	}
}
