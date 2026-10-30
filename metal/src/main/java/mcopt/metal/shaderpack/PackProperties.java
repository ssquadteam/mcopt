package mcopt.metal.shaderpack;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

public final class PackProperties {
	public record Blend(boolean off, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
		public static final Blend OFF = new Blend(true, 0, 0, 0, 0);
	}

	public record AlphaTest(boolean off, int compare, float reference) {
		public static final AlphaTest OFF = new AlphaTest(true, 0, 0);
	}

	public record CustomUniform(boolean variable, String type, String name, String expression) {
	}

	private final Map<String, String> entries;
	private final List<CustomUniform> uniforms;
	private final Map<String, String> macros;

	private PackProperties(Map<String, String> entries, List<CustomUniform> uniforms, Map<String, String> macros) {
		this.entries = entries;
		this.uniforms = uniforms;
		this.macros = macros;
	}

	public static PackProperties empty() {
		return new PackProperties(Map.of(), List.of(), Map.of());
	}

	public static PackProperties parse(@Nullable String text, Map<String, String> macros) {
		if (text == null) return new PackProperties(Map.of(), List.of(), macros);
		Map<String, String> defines = new HashMap<>(macros);
		String pre = preprocess(text, defines);
		Map<String, String> entries = new LinkedHashMap<>();
		List<CustomUniform> uniforms = new ArrayList<>();
		Properties p = new Properties() {
			@Override
			public synchronized Object put(Object key, Object value) {
				String k = key.toString().trim(), v = value.toString().trim();
				entries.put(k, v);
				if (k.startsWith("uniform.") || k.startsWith("variable.")) {
					String[] parts = k.split("\\.", 3);
					if (parts.length == 3) {
						boolean variable = parts[0].equals("variable");
						uniforms.removeIf(u -> u.name().equals(parts[2]));
						uniforms.add(new CustomUniform(variable, parts[1], parts[2], v));
					}
				}
				return super.put(key, value);
			}
		};
		try {
			p.load(new StringReader(pre));
		} catch (IOException | IllegalArgumentException e) {
			return new PackProperties(entries, uniforms, defines);
		}
		return new PackProperties(entries, uniforms, defines);
	}

	static String preprocess(String text, Map<String, String> defines) {
		StringBuilder out = new StringBuilder(text.length());
		Deque<int[]> stack = new ArrayDeque<>();
		boolean active = true;
		String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		PackExpr.Env env = new PackExpr.Env() {
			@Override
			public double @Nullable [] value(String name) {
				String v = defines.get(name);
				if (v == null) return null;
				return evaluateMacro(v, defines, 0);
			}

			@Override
			public boolean defined(String name) {
				return defines.containsKey(name);
			}
		};
		for (int i = 0; i < lines.length; i++) {
			String raw = lines[i];
			String line = raw.trim();
			if (line.startsWith("#")) {
				String d = line.substring(1).trim();
				String word = d.split("\\s+", 2)[0];
				String rest = d.length() > word.length() ? d.substring(word.length()).trim() : "";
				int comment = rest.indexOf("//");
				if (comment >= 0) rest = rest.substring(0, comment).trim();
				switch (word) {
					case "ifdef", "ifndef", "if" -> {
						boolean taken = false;
						if (active) {
							taken = switch (word) {
								case "ifdef" -> defines.containsKey(rest);
								case "ifndef" -> !defines.containsKey(rest);
								default -> condition(rest, env);
							};
						}
						stack.push(new int[] {active ? 1 : 0, taken ? 1 : 0});
						active = active && taken;
						out.append('\n');
						continue;
					}
					case "elif" -> {
						int[] top = stack.peek();
						if (top != null) {
							boolean parent = top[0] == 1;
							boolean taken = parent && top[1] == 0 && condition(rest, env);
							if (taken) top[1] = 1;
							active = taken;
						}
						out.append('\n');
						continue;
					}
					case "else" -> {
						int[] top = stack.peek();
						if (top != null) {
							active = top[0] == 1 && top[1] == 0;
							top[1] = 1;
						}
						out.append('\n');
						continue;
					}
					case "endif" -> {
						int[] top = stack.poll();
						active = top == null || top[0] == 1;
						out.append('\n');
						continue;
					}
					case "define" -> {
						if (active && !rest.isEmpty()) {
							String[] kv = rest.split("\\s+", 2);
							defines.put(kv[0], kv.length > 1 ? kv[1].trim() : "");
						}
						out.append('\n');
						continue;
					}
					case "undef" -> {
						if (active) defines.remove(rest);
						out.append('\n');
						continue;
					}
					default -> {
					}
				}
			}
			if (active) out.append(raw);
			out.append('\n');
		}
		return out.toString();
	}

	private static boolean condition(String expression, PackExpr.Env env) {
		try {
			return PackExpr.parse(expression).truth(env);
		} catch (RuntimeException e) {
			return false;
		}
	}

	static double[] evaluateMacro(String value, Map<String, String> defines, int depth) {
		String v = value.trim();
		if (v.isEmpty()) return PackExpr.of(1);
		if (depth > 8) return PackExpr.of(0);
		try {
			return PackExpr.parse(v).eval(new PackExpr.Env() {
				@Override
				public double @Nullable [] value(String name) {
					String inner = defines.get(name);
					return inner == null ? null : evaluateMacro(inner, defines, depth + 1);
				}

				@Override
				public boolean defined(String name) {
					return defines.containsKey(name);
				}
			});
		} catch (RuntimeException e) {
			return PackExpr.of(0);
		}
	}

	public Map<String, String> entries() {
		return Collections.unmodifiableMap(this.entries);
	}

	public Map<String, String> macros() {
		return Collections.unmodifiableMap(this.macros);
	}

	public List<CustomUniform> customUniforms() {
		return Collections.unmodifiableList(this.uniforms);
	}

	public @Nullable String get(String key) {
		return this.entries.get(key);
	}

	public boolean flag(String key, boolean fallback) {
		String v = this.entries.get(key);
		if (v == null) return fallback;
		return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on");
	}

	public @Nullable Boolean programEnabled(String dimension, String program) {
		String rule = this.entries.get("program." + (dimension.isEmpty() ? "" : dimension + "/") + program + ".enabled");
		if (rule == null && !dimension.isEmpty()) rule = this.entries.get("program." + program + ".enabled");
		if (rule == null) return null;
		Map<String, String> defines = this.macros;
		try {
			return PackExpr.parse(rule).truth(new PackExpr.Env() {
				@Override
				public double @Nullable [] value(String name) {
					String v = defines.get(name);
					return v == null ? null : evaluateMacro(v, defines, 0);
				}

				@Override
				public boolean defined(String name) {
					return defines.containsKey(name);
				}
			});
		} catch (RuntimeException e) {
			return null;
		}
	}

	public @Nullable Blend blend(String program, @Nullable String buffer) {
		String v = this.entries.get("blend." + program + (buffer == null ? "" : "." + buffer));
		if (v == null) return null;
		String[] w = v.trim().split("\\s+");
		if (w.length == 1 && w[0].equalsIgnoreCase("off")) return Blend.OFF;
		if (w.length < 4) return null;
		int[] f = new int[4];
		for (int i = 0; i < 4; i++) {
			f[i] = blendFactor(w[i]);
			if (f[i] < 0) return null;
		}
		return new Blend(false, f[0], f[1], f[2], f[3]);
	}

	public static int blendFactor(String s) {
		return switch (s.toUpperCase(Locale.ROOT)) {
			case "ZERO" -> 0;
			case "ONE" -> 1;
			case "SRC_COLOR" -> 2;
			case "ONE_MINUS_SRC_COLOR" -> 3;
			case "SRC_ALPHA" -> 4;
			case "ONE_MINUS_SRC_ALPHA" -> 5;
			case "DST_COLOR" -> 6;
			case "ONE_MINUS_DST_COLOR" -> 7;
			case "DST_ALPHA" -> 8;
			case "ONE_MINUS_DST_ALPHA" -> 9;
			case "SRC_ALPHA_SATURATE" -> 10;
			default -> -1;
		};
	}

	public @Nullable AlphaTest alphaTest(String program) {
		String v = this.entries.get("alphaTest." + program);
		if (v == null) return null;
		String[] w = v.trim().split("\\s+");
		if (w[0].equalsIgnoreCase("off") || w[0].equalsIgnoreCase("false")) return AlphaTest.OFF;
		if (w.length < 2) return null;
		int compare = switch (w[0].toUpperCase(Locale.ROOT)) {
			case "NEVER" -> 0;
			case "LESS" -> 1;
			case "EQUAL" -> 2;
			case "LEQUAL" -> 3;
			case "GREATER" -> 4;
			case "NOTEQUAL" -> 5;
			case "GEQUAL" -> 6;
			case "ALWAYS" -> 7;
			default -> -1;
		};
		if (compare < 0) return null;
		try {
			return new AlphaTest(false, compare, Float.parseFloat(w[1]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public float @Nullable [] scale(String program) {
		String v = this.entries.get("scale." + program);
		if (v == null) return null;
		String[] w = v.trim().split("\\s+");
		try {
			float s = Float.parseFloat(w[0]);
			float x = w.length > 2 ? Float.parseFloat(w[1]) : 0, y = w.length > 2 ? Float.parseFloat(w[2]) : 0;
			return new float[] {s, x, y};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public @Nullable Boolean flip(String program, String buffer) {
		String v = this.entries.get("flip." + program + "." + buffer);
		if (v == null) return null;
		return v.trim().equalsIgnoreCase("true");
	}

	public float @Nullable [] bufferSize(String buffer) {
		String v = this.entries.get("size.buffer." + buffer);
		if (v == null) return null;
		String[] w = v.trim().split("\\s+");
		if (w.length < 2) return null;
		try {
			return new float[] {Float.parseFloat(w[0]), Float.parseFloat(w[1])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public Map<String, String> textures(String stage) {
		Map<String, String> out = new LinkedHashMap<>();
		String prefix = "texture." + stage + ".";
		for (var e : this.entries.entrySet()) if (e.getKey().startsWith(prefix)) out.put(e.getKey().substring(prefix.length()), e.getValue());
		return out;
	}
}
