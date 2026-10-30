package mcopt.metal.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PackMacros {
	public static final List<String> RENDER_STAGES = List.of("NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID",
		"TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE",
		"PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT");
	private static final List<String> DH_BLOCKS = List.of("UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND", "TERRACOTTA",
		"NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED");

	public record Environment(String minecraftVersion, int mipmapLevel, float renderQuality, float shadowQuality, boolean normalMaps, boolean specularMaps,
		int anisotropy) {
		public static final Environment DEFAULT = new Environment("26.3", 4, 1, 1, false, false, 1);
	}

	private PackMacros() {
	}

	public static int mcVersion(String version) {
		String[] parts = version.split("[.-]");
		int value = 0, scale = 10000;
		for (int i = 0; i < 3; i++, scale /= 100) {
			int n = i < parts.length && parts[i].matches("\\d+") ? Integer.parseInt(parts[i]) : 0;
			value += n * scale;
		}
		return value;
	}

	public static Map<String, String> standard(Environment env) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("MC_VERSION", Integer.toString(mcVersion(env.minecraftVersion())));
		m.put("MC_MIPMAP_LEVEL", Integer.toString(env.mipmapLevel()));
		m.put("IRIS_VERSION", "11107");
		m.put("MC_GL_VERSION", "460");
		m.put("MC_GLSL_VERSION", "460");
		m.put("MC_OS_MAC", "");
		m.put("MC_GL_VENDOR_APPLE", "");
		m.put("MC_GL_RENDERER_APPLE", "");
		m.put("MC_GL_ARB_shader_texture_lod", "");
		m.put("MC_GL_EXT_gpu_shader4", "");
		m.put("MC_GL_ARB_gpu_shader5", "");
		m.put("MC_GL_ARB_texture_gather", "");
		m.put("MC_GL_ARB_shading_language_packing", "");
		m.put("MC_GL_ARB_shader_bit_encoding", "");
		m.put("IS_IRIS", "");
		m.put("IRIS_TAG_SUPPORT", "2");
		m.put("IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS", "");
		m.put("IRIS_HAS_TRANSLUCENCY_SORTING", "");
		m.put("IRIS_FEATURE_SEPARATE_HARDWARE_SAMPLERS", "");
		m.put("IRIS_FEATURE_ENTITY_TRANSLUCENT", "");
		m.put("MAX_COLOR_BUFFERS", "16");
		for (int i = 0; i < DH_BLOCKS.size(); i++) m.put("DH_BLOCK_" + DH_BLOCKS.get(i), Integer.toString(i));
		if (env.normalMaps()) m.put("MC_NORMAL_MAP", "");
		if (env.specularMaps()) m.put("MC_SPECULAR_MAP", "");
		m.put("MC_RENDER_QUALITY", Float.toString(env.renderQuality()));
		m.put("MC_SHADOW_QUALITY", Float.toString(env.shadowQuality()));
		m.put("MC_HAND_DEPTH", "0.125");
		if (env.anisotropy() > 1) m.put("MC_ANISOTROPIC_FILTERING", Integer.toString(env.anisotropy()));
		for (int i = 0; i < RENDER_STAGES.size(); i++) m.put("MC_RENDER_STAGE_" + RENDER_STAGES.get(i), Integer.toString(i));
		return m;
	}

	public static Map<String, String> stage(String stage) {
		Map<String, String> m = new LinkedHashMap<>();
		switch (stage) {
			case "vertex" -> m.put("MC_VERTEX_SHADER", "");
			case "fragment" -> m.put("MC_FRAGMENT_SHADER", "");
			case "geometry" -> m.put("MC_GEOMETRY_SHADER", "");
			case "compute" -> m.put("MC_COMPUTE_SHADER", "");
			default -> {
			}
		}
		return m;
	}
}
