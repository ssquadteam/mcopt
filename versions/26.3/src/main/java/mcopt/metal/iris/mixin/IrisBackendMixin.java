package mcopt.metal.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.metal.iris.IrisMetal;
import mcopt.metal.iris.MetalRenderBackend;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.RenderBackend;
import net.irisshaders.iris.pipeline.GraphicsBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Iris.class, remap = false)
public abstract class IrisBackendMixin {
	@Shadow
	private static RenderBackend renderBackend;

	@Inject(method = "initializeRenderBackend", at = @At("HEAD"), cancellable = true)
	private static void mcopt$metal(CallbackInfo ci) {
		if (!IrisMetal.metalDevice()) return;
		GraphicsBackend.initialize(RenderSystem.getDevice());
		IrisMetal.active = true;
		renderBackend = new MetalRenderBackend();
		ci.cancel();
	}
}
