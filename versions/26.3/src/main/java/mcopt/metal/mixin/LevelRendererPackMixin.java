package mcopt.metal.mixin;

import mcopt.metal.shaderpack.render.PackRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererPackMixin {
	@ModifyVariable(method = "lambda$addMainPass$0", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean mcopt$classicTransparency(boolean oit) {
		return oit && !PackRuntime.forceClassicTransparency();
	}

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"))
	private void mcopt$beforeTranslucent(CallbackInfo ci) {
		PackRuntime.beforeTranslucent();
	}
}
