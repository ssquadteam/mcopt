package mcopt.metal.mixin.render;

import mcopt.metal.render.Particles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Particle.class)
abstract class ParticleLightMixin {
	@Shadow @Final protected ClientLevel level;
	@Shadow protected double x;
	@Shadow protected double y;
	@Shadow protected double z;

	@Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
	private void mcopt$cachedLight(float partialTick, CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(Particles.light(this.level, this.x, this.y, this.z));
	}
}
