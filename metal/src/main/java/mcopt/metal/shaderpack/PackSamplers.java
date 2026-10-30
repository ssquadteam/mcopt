package mcopt.metal.shaderpack;

import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class PackSamplers implements ShaderCompiler.Bindings {
	public static final int ALBEDO = 0, OVERLAY = 1, LIGHTMAP = 2, NORMALS = 3, SPECULAR = 4, BLOCK_IDS = 5, FIRST = 8;
	private static final Map<String, Integer> DYNAMIC = Map.ofEntries(Map.entry("gtexture", ALBEDO), Map.entry("texture", ALBEDO),
		Map.entry("iris_texture", ALBEDO), Map.entry("tex", ALBEDO), Map.entry("gcolor_game", ALBEDO), Map.entry("lightmap", LIGHTMAP),
		Map.entry("iris_overlay", OVERLAY), Map.entry("normals", NORMALS), Map.entry("specular", SPECULAR));

	private final Map<String, String> consts;
	private final boolean gbuffers;
	private final Map<String, ShaderCompiler.Sampler> custom;
	private final Map<String, Integer> slots = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(FIRST);

	@Override
	public int textureSlot(String name) {
		if (this.gbuffers || !name.equals("texture") && !name.equals("tex")) {
			Integer dynamic = DYNAMIC.get(name);
			if (dynamic != null) return dynamic;
		}
		return this.slots.computeIfAbsent(name, n -> this.next.getAndIncrement());
	}

	public PackSamplers(Map<String, String> consts, boolean gbuffers) {
		this(consts, gbuffers, Map.of());
	}

	public PackSamplers(Map<String, String> consts, boolean gbuffers, Map<String, ShaderCompiler.Sampler> custom) {
		this.consts = consts;
		this.gbuffers = gbuffers;
		this.custom = custom;
	}

	public static String customKey(String name) {
		String buffer = PackBuffers.canonical(name);
		return buffer == null ? name : buffer;
	}

	public static @Nullable Integer dynamicSlot(String name) {
		return DYNAMIC.get(name);
	}

	@Override
	public int[] fixed(int set, int binding) {
		if (set != ShaderCompiler.FIXED_SET) return new int[] {Adapters.PUSH_CONSTANTS_SLOT, 0, 0};
		return switch (binding) {
			case Adapters.DYNAMIC_TRANSFORMS -> new int[] {Adapters.DYNAMIC_TRANSFORMS_SLOT, 0, 0};
			case Adapters.PROJECTION -> new int[] {Adapters.PROJECTION_SLOT, 0, 0};
			case Adapters.SODIUM_GLOBALS -> new int[] {Adapters.SODIUM_GLOBALS_SLOT, 0, 0};
			case Adapters.FRAME -> new int[] {Adapters.FRAME_SLOT, 0, 0};
			case Adapters.BLOCK_IDS -> new int[] {0, BLOCK_IDS, 0};
			case Adapters.SODIUM_VERTICES -> new int[] {Adapters.SODIUM_VERTICES_SLOT, 0, 0};
			default -> new int[] {binding, binding, binding};
		};
	}

	private boolean on(String key, boolean fallback) {
		String v = this.consts.get(key);
		if (v == null) return fallback;
		return v.equals("true") || v.equals("1");
	}

	@Override
	public ShaderCompiler.@Nullable Sampler sampler(String name, boolean depth, int dimension) {
		if (DYNAMIC.containsKey(name) && (this.gbuffers || !name.equals("texture") && !name.equals("tex"))) return null;
		ShaderCompiler.Sampler custom = this.custom.get(customKey(name));
		if (custom != null) return custom;
		if (name.startsWith("shadowtex") || name.equals("shadow") || name.equals("watershadow")) {
			boolean nearest = on(base(name) + "Nearest", false) || on("shadowtexNearest", false);
			return new ShaderCompiler.Sampler(!nearest, on(base(name) + "Mipmap", false), false, depth);
		}
		if (name.startsWith("shadowcolor")) {
			boolean nearest = on(name + "Nearest", false) || on("shadowcolorNearest", false);
			return new ShaderCompiler.Sampler(!nearest, on(name + "Mipmap", false), false, false);
		}
		if (name.startsWith("depthtex") || name.equals("gdepthtex")) return ShaderCompiler.Sampler.NEAREST;
		if (name.equals("noisetex")) return new ShaderCompiler.Sampler(true, false, true, false);
		int buffer = PackBuffers.index(name);
		if (buffer >= 0) return new ShaderCompiler.Sampler(true, PackBuffers.mipmapped(this.consts, buffer), false, false);
		if (name.equals("lightmap")) return ShaderCompiler.Sampler.LINEAR;
		return new ShaderCompiler.Sampler(true, false, true, false);
	}

	private static String base(String name) {
		return name.endsWith("HW") ? name.substring(0, name.length() - 2) : name;
	}

	@Override
	public int dynamicSampler(String name) {
		Integer slot = DYNAMIC.get(name);
		return slot == null ? ALBEDO : slot;
	}
}
