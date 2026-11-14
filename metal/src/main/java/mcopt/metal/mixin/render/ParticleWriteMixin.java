package mcopt.metal.mixin.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import mcopt.metal.render.Particles;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleRenderState.class)
abstract class ParticleWriteMixin {
	@Shadow @Final private Map<SingleQuadParticle.Layer, Object> particles;

	@Inject(method = "buildLayer", at = @At("HEAD"), cancellable = true)
	private void mcopt$writeLayer(SingleQuadParticle.Layer layer, VertexConsumer builder, CallbackInfo ci) {
		Object storage = this.particles.get(layer);
		if (storage == null || Particles.write((ParticleStorageAccess) storage, builder)) ci.cancel();
	}
}
