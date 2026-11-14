package mcopt.metal.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import mcopt.metal.render.GuiItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphicsExtractor.class)
abstract class GuiItemMixin {
	private static final String ITEM = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V";

	@WrapOperation(method = ITEM, at = @At(value = "NEW", target = "net/minecraft/client/renderer/item/TrackingItemStackRenderState"))
	private TrackingItemStackRenderState mcopt$reuse(Operation<TrackingItemStackRenderState> original, @Local(argsOnly = true) @Nullable LivingEntity owner,
		@Local(argsOnly = true) @Nullable Level level, @Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true, ordinal = 2) int seed) {
		TrackingItemStackRenderState kept = GuiItems.reuse(Minecraft.getInstance().getItemModelResolver(), stack, level, owner, seed);
		return kept != null ? kept : original.call();
	}

	@WrapOperation(method = ITEM, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemModelResolver;updateForTopItem(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V"))
	private void mcopt$resolve(ItemModelResolver resolver, ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, @Nullable Level level,
		@Nullable ItemOwner owner, int seed, Operation<Void> original) {
		GuiItems.resolve(resolver, (TrackingItemStackRenderState) state, stack, level, owner, seed, () -> original.call(resolver, state, stack, context, level, owner, seed));
	}
}
