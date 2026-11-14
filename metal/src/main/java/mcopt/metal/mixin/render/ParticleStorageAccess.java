package mcopt.metal.mixin.render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.state.level.QuadParticleRenderState$Storage")
public interface ParticleStorageAccess {
	@Accessor("floatValues")
	float[] mcopt$floats();

	@Accessor("intValues")
	int[] mcopt$ints();

	@Accessor("currentParticleIndex")
	int mcopt$count();
}
