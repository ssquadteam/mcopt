package mcopt.metal.iris.mixin;

import net.irisshaders.iris.pipeline.GraphicsBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GraphicsBackend.class, remap = false)
public abstract class GraphicsBackendMixin {
	@Inject(method = "fromName", at = @At("HEAD"), cancellable = true)
	private static void mcopt$metal(String name, CallbackInfoReturnable<GraphicsBackend> cir) {
		if ("Metal".equalsIgnoreCase(name)) cir.setReturnValue(GraphicsBackend.VULKAN);
	}
}
