package mcopt.metal.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mcopt.metal.render.EntityStates;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
abstract class EntityExtractMixin {
	@WrapOperation(method = "extractEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState mcopt$reusing(EntityRenderDispatcher dispatcher, Entity entity, float partialTicks, Operation<EntityRenderState> original) {
		EntityStates.extracting(true);
		try {
			return original.call(dispatcher, entity, partialTicks);
		} finally {
			EntityStates.extracting(false);
		}
	}
}
