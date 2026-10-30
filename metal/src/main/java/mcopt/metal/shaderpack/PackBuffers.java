package mcopt.metal.shaderpack;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class PackBuffers {
	public static final int MAX = 16;
	private static final Map<String, Integer> LEGACY = Map.of("gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3, "gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7);

	private PackBuffers() {
	}

	public static int index(String name) {
		Integer legacy = LEGACY.get(name);
		if (legacy != null) return legacy;
		if (name.startsWith("colortex")) {
			try {
				int i = Integer.parseInt(name.substring(8));
				return i >= 0 && i < MAX ? i : -1;
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		return -1;
	}

	public static @Nullable String canonical(String name) {
		int i = index(name);
		return i < 0 ? null : "colortex" + i;
	}

	public static String format(Map<String, String> consts, int buffer) {
		String v = constant(consts, buffer, "Format");
		return v == null ? "RGBA8" : v.trim().toUpperCase(Locale.ROOT);
	}

	public static boolean mipmapped(Map<String, String> consts, int buffer) {
		String v = constant(consts, buffer, "MipmapEnabled");
		return v != null && (v.trim().equals("true") || v.trim().equals("1"));
	}

	public static @Nullable String constant(Map<String, String> consts, int buffer, String suffix) {
		String v = consts.get("colortex" + buffer + suffix);
		if (v != null) return v;
		for (var e : LEGACY.entrySet()) if (e.getValue() == buffer && consts.containsKey(e.getKey() + suffix)) return consts.get(e.getKey() + suffix);
		return null;
	}

	public static int metalFormat(String glFormat) {
		return switch (glFormat) {
			case "R8" -> 10;
			case "RG8" -> 30;
			case "RGB8", "RGBA8", "RGBA" -> 70;
			case "R8_SNORM" -> 12;
			case "RG8_SNORM" -> 32;
			case "RGB8_SNORM", "RGBA8_SNORM" -> 72;
			case "R16" -> 20;
			case "RG16" -> 60;
			case "RGB16", "RGBA16" -> 110;
			case "R16_SNORM" -> 22;
			case "RG16_SNORM" -> 62;
			case "RGB16_SNORM", "RGBA16_SNORM" -> 112;
			case "R16F" -> 25;
			case "RG16F" -> 65;
			case "RGB16F", "RGBA16F" -> 115;
			case "R32F" -> 55;
			case "RG32F" -> 105;
			case "RGB32F", "RGBA32F" -> 125;
			case "R11F_G11F_B10F" -> 92;
			case "RGB10_A2", "RGB10_A2UI" -> 90;
			case "RGB9_E5" -> 93;
			case "R8I" -> 14;
			case "RG8I" -> 34;
			case "RGB8I", "RGBA8I" -> 74;
			case "R8UI" -> 13;
			case "RG8UI" -> 33;
			case "RGB8UI", "RGBA8UI" -> 73;
			case "R16I" -> 24;
			case "RG16I" -> 64;
			case "RGB16I", "RGBA16I" -> 114;
			case "R16UI" -> 23;
			case "RG16UI" -> 63;
			case "RGB16UI", "RGBA16UI" -> 113;
			case "R32I" -> 54;
			case "RG32I" -> 104;
			case "RGB32I", "RGBA32I" -> 124;
			case "R32UI" -> 53;
			case "RG32UI" -> 103;
			case "RGB32UI", "RGBA32UI" -> 123;
			default -> 70;
		};
	}
}
