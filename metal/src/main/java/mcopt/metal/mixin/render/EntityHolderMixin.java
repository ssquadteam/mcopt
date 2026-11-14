package mcopt.metal.mixin.render;

import mcopt.metal.render.EntityStates;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
abstract class EntityHolderMixin implements EntityStates.Holder {
	@Unique private @Nullable EntityRenderState mcopt$state;
	@Unique private @Nullable Object mcopt$stateRenderer;

	@Override
	public @Nullable EntityRenderState mcopt$state() {
		return this.mcopt$state;
	}

	@Override
	public @Nullable Object mcopt$stateRenderer() {
		return this.mcopt$stateRenderer;
	}

	@Override
	public void mcopt$keep(EntityRenderState state, Object renderer) {
		this.mcopt$state = state;
		this.mcopt$stateRenderer = renderer;
	}
}
