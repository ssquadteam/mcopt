package mcopt.metal.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * -Dmcopt.render.entityReuse: players and dropped items keep their render state from one frame to the next and the world
 * extraction fills it in again, instead of building a new one (with its item, arm and equipment states) every frame. Their
 * renderers overwrite every field each frame. Only the world's own extraction reuses; inventory previews and other callers
 * get a new state as before.
 */
public final class EntityStates {
	public interface Holder {
		@Nullable EntityRenderState mcopt$state();

		@Nullable Object mcopt$stateRenderer();

		void mcopt$keep(EntityRenderState state, Object renderer);
	}

	private static @Nullable Thread extracting;

	private EntityStates() {
	}

	public static void extracting(boolean on) {
		extracting = on ? Thread.currentThread() : null;
	}

	public static EntityRenderState create(EntityRenderer<?, ?> renderer, Entity entity, Operation<EntityRenderState> original) {
		if (extracting != Thread.currentThread()) return original.call(renderer);
		extracting = null;
		if (!(renderer instanceof AvatarRenderer<?> || renderer instanceof ItemEntityRenderer)) return original.call(renderer);
		Holder holder = (Holder) entity;
		EntityRenderState state = holder.mcopt$state();
		if (state != null && holder.mcopt$stateRenderer() == renderer) return state;
		state = original.call(renderer);
		holder.mcopt$keep(state, renderer);
		return state;
	}
}
