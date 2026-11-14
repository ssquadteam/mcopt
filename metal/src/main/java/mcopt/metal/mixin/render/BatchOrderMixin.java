package mcopt.metal.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Collection;
import java.util.Map;
import mcopt.metal.render.Batches;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SimpleFeatureRenderPhase.class)
abstract class BatchOrderMixin {
	@WrapOperation(method = "sortFeatureInto", at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;"))
	private static Collection<Object> mcopt$byPipeline(Map<Object, Object> batches, Operation<Collection<Object>> original) {
		return Batches.order(batches, original.call(batches));
	}
}
