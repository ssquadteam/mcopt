package mcopt.metal.mixin.render;

import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ItemModelResolver.class)
public interface ItemModelResolverAccess {
	@Invoker("getItemModel")
	ItemModel mcopt$getItemModel(Identifier id);
}
