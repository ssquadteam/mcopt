package mcopt.metal.shaderpack.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mcopt.metal.MetalBridge;
import mcopt.metal.shaderpack.PackCompiler;
import mcopt.metal.shaderpack.PackProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryUtil;

final class BlockIds {
	static final int SIZE = 512;
	private static final int R16_UINT = 23;
	private static final Direction[] FACES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private BlockIds() {
	}

	static long build(long ctx, Object encoder, PackCompiler compiler, List<Long> staging) {
		long tex = MetalBridge.texture(ctx, R16_UINT, SIZE, SIZE, 1, 1);
		long buf = MetalBridge.newBuffer(ctx, (long) SIZE * SIZE * 2);
		long at = MetalBridge.bufferContents(buf);
		MemoryUtil.memSet(at, 0, (long) SIZE * SIZE * 2);
		try {
			Map<String, Integer> ids = ids(compiler);
			if (!ids.isEmpty()) fill(at, ids);
		} catch (Exception e) {
			System.out.println("[mcopt] shaderpack: block ids unavailable: " + e);
		}
		MetalBridge.upload(MetalBridge.enc(encoder), buf, 0, SIZE * 2, tex, SIZE, SIZE);
		staging.add(buf);
		return tex;
	}

	private static Map<String, Integer> ids(PackCompiler compiler) throws Exception {
		String text = compiler.source().read((compiler.dimension().isEmpty() ? "" : compiler.dimension() + "/") + "block.properties");
		if (text == null) text = compiler.source().read("block.properties");
		Map<String, Integer> plain = new HashMap<>(), filtered = new HashMap<>();
		if (text == null) return plain;
		PackProperties props = PackProperties.parse(text, compiler.properties().macros());
		for (var e : props.entries().entrySet()) {
			if (!e.getKey().startsWith("block.")) continue;
			int id;
			try {
				id = Integer.parseInt(e.getKey().substring(6).trim());
			} catch (NumberFormatException ex) {
				continue;
			}
			for (String raw : e.getValue().trim().split("\\s+")) {
				if (raw.isEmpty() || raw.startsWith("#")) continue;
				String[] parts = raw.toLowerCase(Locale.ROOT).split(":");
				int states = 0;
				while (states < parts.length && !parts[states].contains("=")) states++;
				String block = switch (states) {
					case 1 -> "minecraft:" + parts[0];
					case 2 -> parts[0] + ":" + parts[1];
					default -> null;
				};
				if (block == null) continue;
				(states < parts.length ? filtered : plain).putIfAbsent(block, id);
			}
		}
		filtered.forEach(plain::putIfAbsent);
		return plain;
	}

	private static void fill(long at, Map<String, Integer> ids) {
		var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		Map<TextureAtlasSprite, Map<Integer, Integer>> votes = new HashMap<>();
		List<BlockStateModelPart> parts = new ArrayList<>();
		RandomSource random = RandomSource.create(42L);
		for (Block block : BuiltInRegistries.BLOCK) {
			Integer id = ids.get(BuiltInRegistries.BLOCK.getKey(block).toString());
			int value = id == null ? 0 : id;
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				parts.clear();
				random.setSeed(42L);
				models.get(state).collectParts(random, parts);
				for (BlockStateModelPart part : parts) {
					for (Direction face : FACES) {
						for (BakedQuad quad : part.getQuads(face)) votes.computeIfAbsent(quad.materialInfo().sprite(), s -> new HashMap<>()).merge(value, 1, Integer::sum);
					}
				}
			}
		}
		for (var e : votes.entrySet()) {
			int id = winner(e.getValue());
			if (id != 0) mark(at, e.getKey(), id);
		}
		var fluids = Minecraft.getInstance().getModelManager().getFluidStateModelSet();
		for (Fluid fluid : BuiltInRegistries.FLUID) {
			FluidState state = fluid.defaultFluidState();
			if (state.isEmpty()) continue;
			Integer id = ids.get(BuiltInRegistries.BLOCK.getKey(state.createLegacyBlock().getBlock()).toString());
			if (id == null) continue;
			FluidModel model = fluids.get(state);
			mark(at, model.stillMaterial().sprite(), id);
			mark(at, model.flowingMaterial().sprite(), id);
		}
	}

	private static int winner(Map<Integer, Integer> counts) {
		int best = 0, bestCount = -1;
		for (var c : counts.entrySet()) {
			if (c.getValue() > bestCount || c.getValue() == bestCount && best == 0) {
				best = c.getKey();
				bestCount = c.getValue();
			}
		}
		return best;
	}

	private static void mark(long at, TextureAtlasSprite sprite, int id) {
		int x0 = (int) Math.floor(sprite.getU0() * SIZE), x1 = (int) Math.ceil(sprite.getU1() * SIZE);
		int y0 = (int) Math.floor(sprite.getV0() * SIZE), y1 = (int) Math.ceil(sprite.getV1() * SIZE);
		for (int y = Math.max(0, y0); y < Math.min(SIZE, Math.max(y1, y0 + 1)); y++) {
			for (int x = Math.max(0, x0); x < Math.min(SIZE, Math.max(x1, x0 + 1)); x++) MemoryUtil.memPutShort(at + ((long) y * SIZE + x) * 2, (short) id);
		}
	}
}
