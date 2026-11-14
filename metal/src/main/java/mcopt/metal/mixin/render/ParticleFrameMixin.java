package mcopt.metal.mixin.render;

import mcopt.metal.render.Particles;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
abstract class ParticleFrameMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void mcopt$beginFrame(CallbackInfo ci) {
		Particles.beginFrame();
	}
}
