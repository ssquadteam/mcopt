package mcopt.metal.mixin;

import mcopt.metal.shaderpack.render.PackRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class LevelRendererPackMixin {
	@Inject(method = "getTransparencyChain", at = @At("HEAD"), cancellable = true)
	private void mcopt$classicTransparency(CallbackInfoReturnable<PostChain> cir) {
		if (PackRuntime.forceClassicTransparency()) cir.setReturnValue(null);
	}

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent()V"))
	private void mcopt$beforeTranslucent(CallbackInfo ci) {
		PackRuntime.beforeTranslucent();
	}
}
