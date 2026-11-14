package mcopt.metal.mixin.render;

import java.util.List;
import java.util.Set;
import mcopt.metal.render.RenderFlags;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Each mixin applies only when its -Dmcopt.render.* flag is on. */
public final class RenderMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply();
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return switch (mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)) {
			case "ParticleCullMixin" -> RenderFlags.PARTICLE_CULL;
			case "ParticleLightMixin" -> RenderFlags.PARTICLE_LIGHT;
			case "ParticleExtractMixin" -> RenderFlags.PARTICLE_EXTRACT;
			case "ParticleWriteMixin", "ParticleStorageAccess" -> RenderFlags.PARTICLE_WRITE;
			case "ParticleFrameMixin" -> RenderFlags.PARTICLE_CULL || RenderFlags.PARTICLE_LIGHT || RenderFlags.PARTICLE_EXTRACT || RenderFlags.PARTICLE_WRITE;
			case "BatchOrderMixin" -> RenderFlags.BATCH_ORDER;
			case "GuiItemMixin", "GuiItemFrameMixin", "ItemModelResolverAccess", "ItemConditionalMixin", "ItemSelectMixin", "ItemRangeMixin", "ItemTintMixin" -> RenderFlags.GUI_ITEMS;
			case "TextCacheMixin", "TextReloadMixin" -> RenderFlags.TEXT_CACHE;
			case "EntityHolderMixin", "EntityExtractMixin", "EntityStateMixin" -> RenderFlags.ENTITY_REUSE;
			case "PosePoolMixin", "PoseDrainMixin" -> RenderFlags.POSE_POOL;
			default -> false;
		};
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
