/*
 * This file is copied or adapted from Sodium (https://github.com/CaffeineMC/sodium), Copyright JellySquid
 * (jellysquid3) and contributors. Unlike the rest of mcopt, it is licensed under the PolyForm Shield License 1.0.0
 * (LICENSES/PolyForm-Shield-1.0.0.md, https://polyformproject.org/licenses/shield/1.0.0), not Apache-2.0.
 * See NOTICE.
 */
package mcopt.metal.mixin.chunk;

import java.util.function.Predicate;
import net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.caffeinemc.mods.sodium.client.services.PlatformModelEmitter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * C1 (-Dmcopt.chunk.mesh=lambda): BlockRenderer.renderModel builds two bound method references (this::isFaceCulled,
 * this::bufferDefaultModel) for every block it meshes; on the Neo they were 58% of the mesher threads' allocation (~190 MB per
 * full rebuild of the fixture scene). The same references, made once per renderer, are passed instead: they call the same methods
 * on the same object, so the emitted quads are the same. The rest of the method is the original, line for line.
 * Priority 900 so other mods (Polytone) can still inject into the overwritten method.
 */
@Mixin(value = BlockRenderer.class, remap = false, priority = 900)
abstract class BlockRendererMixin extends AbstractBlockRenderContext {
	@Shadow @Final private ColorProviderRegistry colorProviderRegistry;
	@Shadow @Final private Vector3f posOffset;
	@Shadow private ColorProvider<BlockState> colorProvider;
	@Shadow @Final private boolean cutoutLeaves;
	@Unique private Predicate<Direction> mcopt$culled;
	@Unique private PlatformModelEmitter.Bufferer mcopt$bufferer;

	/**
	 * @author mcopt
	 * @reason reuse the two method references (opt-in); otherwise unchanged.
	 */
	@Overwrite
	public void renderModel(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin) {
		this.state = state;
		this.pos = pos;
		this.prepareAoInfo(true);
		this.posOffset.set(origin.getX(), origin.getY(), origin.getZ());
		if (state.hasOffsetFunction()) {
			Vec3 modelOffset = state.getOffset(pos);
			this.posOffset.add((float) modelOffset.x, (float) modelOffset.y, (float) modelOffset.z);
		}

		this.colorProvider = this.colorProviderRegistry.getColorProvider(state.getBlock());
		this.prepareCulling(true);
		this.random.setSeed(state.getSeed(pos));
		this.forceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);
		if (this.mcopt$culled == null) {
			this.mcopt$culled = this::isFaceCulled;
			this.mcopt$bufferer = this::bufferDefaultModel;
		}
		PlatformModelEmitter.getInstance()
			.emitModel(model, this.mcopt$culled, this.getForEmitting(), this.random, this.level, pos, state, this.mcopt$bufferer);
		this.forceOpaque = false;
	}
}
