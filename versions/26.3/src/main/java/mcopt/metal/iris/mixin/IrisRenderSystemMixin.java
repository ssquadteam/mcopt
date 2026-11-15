package mcopt.metal.iris.mixin;

import mcopt.metal.iris.IrisMetal;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = IrisRenderSystem.class, remap = false)
public abstract class IrisRenderSystemMixin {
	@Inject(method = "supportsTesselation", at = @At("HEAD"), cancellable = true)
	private static void mcopt$metal(CallbackInfoReturnable<Boolean> cir) {
		if (IrisMetal.active) cir.setReturnValue(false);
	}
}
