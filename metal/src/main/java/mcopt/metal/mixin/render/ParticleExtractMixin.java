package mcopt.metal.mixin.render;

import mcopt.metal.render.Particles;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SingleQuadParticle.class)
abstract class ParticleExtractMixin {
	@Redirect(method = "extract", at = @At(value = "NEW", target = "()Lorg/joml/Quaternionf;"))
	private Quaternionf mcopt$sharedRotation() {
		return Particles.rotation();
	}
}
