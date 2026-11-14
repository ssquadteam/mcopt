package mcopt.metal.render;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import mcopt.metal.mixin.render.ItemModelResolverAccess;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * -Dmcopt.render.guiItems: a GUI item's resolved model is kept and reused while its stack stays the same, instead of being
 * resolved again every frame. Only models that depend on nothing but the stack are kept: every property, tint and model
 * class touched while resolving must be on the lists below, so compasses, clocks, cooldowns, heads and modded models are
 * resolved every frame as before.
 */
public final class GuiItems {
	private static final Set<String> PURE = Set.of(
		"net.minecraft.client.renderer.item.properties.conditional.Broken",
		"net.minecraft.client.renderer.item.properties.conditional.BundleHasSelectedItem",
		"net.minecraft.client.renderer.item.properties.conditional.ComponentMatches",
		"net.minecraft.client.renderer.item.properties.conditional.CustomModelDataProperty",
		"net.minecraft.client.renderer.item.properties.conditional.Damaged",
		"net.minecraft.client.renderer.item.properties.conditional.HasComponent",
		"net.minecraft.client.renderer.item.properties.numeric.BundleFullness",
		"net.minecraft.client.renderer.item.properties.numeric.Count",
		"net.minecraft.client.renderer.item.properties.numeric.CustomModelDataProperty",
		"net.minecraft.client.renderer.item.properties.numeric.Damage",
		"net.minecraft.client.renderer.item.properties.select.Charge",
		"net.minecraft.client.renderer.item.properties.select.ComponentContents",
		"net.minecraft.client.renderer.item.properties.select.CustomModelDataProperty",
		"net.minecraft.client.renderer.item.properties.select.DisplayContext",
		"net.minecraft.client.renderer.item.properties.select.ItemBlockState",
		"net.minecraft.client.renderer.item.properties.select.TrimMaterialProperty",
		"net.minecraft.client.color.item.Constant",
		"net.minecraft.client.color.item.CustomModelDataSource",
		"net.minecraft.client.color.item.Dye",
		"net.minecraft.client.color.item.Firework",
		"net.minecraft.client.color.item.GrassColorSource",
		"net.minecraft.client.color.item.MapColor",
		"net.minecraft.client.color.item.Potion");
	private static final Set<String> MODELS = Set.of(
		"net.minecraft.client.renderer.item.CompositeModel",
		"net.minecraft.client.renderer.item.ConditionalItemModel",
		"net.minecraft.client.renderer.item.CuboidItemModelWrapper",
		"net.minecraft.client.renderer.item.EmptyModel",
		"net.minecraft.client.renderer.item.MissingItemModel",
		"net.minecraft.client.renderer.item.RangeSelectItemModel",
		"net.minecraft.client.renderer.item.SelectItemModel");
	private static final ClassValue<Boolean> PURE_CLASS = named(PURE);
	private static final ClassValue<Boolean> KNOWN_MODEL = named(MODELS);
	private static final int MAX_ENTRIES = 1024;

	private static final class Entry {
		ItemStack copy;
		Item item;
		int count;
		@Nullable Object level;
		@Nullable Object owner;
		int seed;
		@Nullable ItemModel model;
		TrackingItemStackRenderState state;
		int frame;
	}

	private static final IdentityHashMap<ItemStack, Entry> entries = new IdentityHashMap<>();
	private static @Nullable Thread owner;
	private static int frame;
	private static boolean tracking;
	private static boolean dynamic;
	private static boolean reused;

	private GuiItems() {
	}

	private static ClassValue<Boolean> named(Set<String> names) {
		return new ClassValue<>() {
			@Override
			protected Boolean computeValue(Class<?> type) {
				return names.contains(type.getName());
			}
		};
	}

	public static void observe(Object property) {
		if (tracking && !PURE_CLASS.get(property.getClass())) dynamic = true;
	}

	public static void observeTints(List<?> tints) {
		if (!tracking) return;
		for (Object tint : tints) {
			if (!PURE_CLASS.get(tint.getClass())) {
				dynamic = true;
				return;
			}
		}
	}

	public static void frame() {
		if (++frame % 128 == 0) entries.values().removeIf(e -> frame - e.frame > 128);
	}

	private static boolean onOwner() {
		if (owner == null) owner = Thread.currentThread();
		return owner == Thread.currentThread();
	}

	private static @Nullable ItemModel model(ItemModelResolver resolver, ItemStack stack) {
		Identifier id = stack.get(DataComponents.ITEM_MODEL);
		return id == null ? null : ((ItemModelResolverAccess) resolver).mcopt$getItemModel(id);
	}

	/** The render state to fill for this stack: the kept one when it still applies, so the resolve that follows is skipped. */
	public static @Nullable TrackingItemStackRenderState reuse(ItemModelResolver resolver, ItemStack stack, @Nullable Object level, @Nullable Object owner, int seed) {
		reused = false;
		if (!onOwner()) return null;
		Entry e = entries.get(stack);
		if (e == null || e.item != stack.getItem() || e.count != stack.getCount() || e.seed != seed || e.level != level || e.owner != owner
			|| !ItemStack.isSameItemSameComponents(e.copy, stack) || e.model != model(resolver, stack)) {
			return null;
		}
		e.frame = frame;
		reused = true;
		return e.state;
	}

	/** Resolves the stack, unless {@link #reuse} just handed out a kept state, and keeps the result when it depends on the stack alone. */
	public static void resolve(ItemModelResolver resolver, TrackingItemStackRenderState state, ItemStack stack, @Nullable Object level, @Nullable Object owner, int seed, Runnable original) {
		if (reused) {
			reused = false;
			return;
		}
		if (!onOwner()) {
			original.run();
			return;
		}
		tracking = true;
		dynamic = false;
		try {
			original.run();
		} finally {
			tracking = false;
		}
		ItemModel model = model(resolver, stack);
		if (dynamic || model != null && !KNOWN_MODEL.get(model.getClass())) {
			entries.remove(stack);
			return;
		}
		if (state.getModelIdentity() instanceof List<?> identity) {
			for (Object element : identity) {
				if (element instanceof ItemModel m && !KNOWN_MODEL.get(m.getClass())) {
					entries.remove(stack);
					return;
				}
			}
		}
		if (entries.size() >= MAX_ENTRIES && !entries.containsKey(stack)) return;
		Entry e = new Entry();
		e.copy = stack.copy();
		e.item = stack.getItem();
		e.count = stack.getCount();
		e.level = level;
		e.owner = owner;
		e.seed = seed;
		e.model = model;
		e.state = state;
		e.frame = frame;
		entries.put(stack, e);
	}
}
