package mcopt.metal.mixin.render;

import mcopt.metal.render.GuiItems;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
abstract class GuiItemFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$frame(CallbackInfo ci) {
		GuiItems.frame();
	}
}
