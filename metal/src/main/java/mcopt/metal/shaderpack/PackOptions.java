package mcopt.metal.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class PackOptions {
	public enum Kind {
		BOOLEAN, DEFINE, CONST
	}

	public record Option(String name, Kind kind, String defaultValue, List<String> values, String comment) {
		public boolean bool() {
			return this.kind == Kind.BOOLEAN || (this.kind == Kind.CONST && (this.defaultValue.equals("true") || this.defaultValue.equals("false")));
		}
	}

	private static final Pattern DEFINE = Pattern.compile("^\\s*(//\\s*)?#define\\s+([A-Za-z_]\\w*)(?:\\s+([^/\\s][^/]*?|/(?!/)[^/]*?))?\\s*(?://\\s*(.*))?$");
	private static final Pattern CONST = Pattern.compile("^\\s*const\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+?)\\s*;\\s*(?://\\s*(.*))?$");
	private static final Pattern LIST = Pattern.compile("\\[([^\\]]*)\\]");
	private static final Pattern REFERENCE = Pattern.compile("#\\s*(?:ifdef|ifndef|if|elif)\\b([^\\n]*)");
	private static final Pattern WORD = Pattern.compile("[A-Za-z_]\\w*");
	private static final Set<String> SHADER_FILES = Set.of("vsh", "fsh", "gsh", "csh", "tcs", "tes", "glsl", "inc");

	private final Map<String, Option> options;
	private final Map<String, String> values;

	private PackOptions(Map<String, Option> options, Map<String, String> values) {
		this.options = options;
		this.values = values;
	}

	public static boolean shaderFile(String path) {
		int dot = path.lastIndexOf('.');
		return dot > 0 && SHADER_FILES.contains(path.substring(dot + 1));
	}

	public static PackOptions discover(PackSource source, Map<String, String> values) throws IOException {
		Map<String, Option> found = new LinkedHashMap<>();
		Set<String> referenced = new HashSet<>();
		Map<String, Option> booleans = new LinkedHashMap<>();
		for (String file : source.files()) {
			if (!shaderFile(file)) continue;
			String text = source.read(file);
			if (text == null) continue;
			Matcher r = REFERENCE.matcher(text);
			while (r.find()) {
				Matcher w = WORD.matcher(r.group(1));
				while (w.find()) referenced.add(w.group());
			}
			for (String line : text.split("\n")) {
				Option o = parse(line);
				if (o == null) continue;
				if (o.kind() == Kind.BOOLEAN) booleans.putIfAbsent(o.name(), o);
				else found.putIfAbsent(o.name(), o);
			}
		}
		for (Option b : booleans.values()) if (referenced.contains(b.name()) && !found.containsKey(b.name())) found.put(b.name(), b);
		return new PackOptions(found, new LinkedHashMap<>(values));
	}

	static @Nullable Option parse(String line) {
		if (line.indexOf('#') >= 0 && line.contains("define")) {
			Matcher m = DEFINE.matcher(line);
			if (!m.matches()) return null;
			boolean commented = m.group(1) != null;
			String name = m.group(2), value = m.group(3), comment = m.group(4) == null ? "" : m.group(4).trim();
			if (value == null || value.isBlank()) return new Option(name, Kind.BOOLEAN, commented ? "false" : "true", List.of("true", "false"), comment);
			if (commented) return null;
			value = value.trim();
			return new Option(name, Kind.DEFINE, value, list(comment), comment);
		}
		if (line.contains("const")) {
			Matcher m = CONST.matcher(line);
			if (!m.matches()) return null;
			String comment = m.group(4) == null ? "" : m.group(4).trim();
			List<String> list = list(comment);
			if (m.group(1).equals("bool")) return new Option(m.group(2), Kind.CONST, m.group(3).trim(), List.of("true", "false"), comment);
			return new Option(m.group(2), Kind.CONST, m.group(3).trim(), list, comment);
		}
		return null;
	}

	private static List<String> list(String comment) {
		Matcher l = LIST.matcher(comment);
		if (!l.find()) return List.of();
		List<String> out = new ArrayList<>();
		for (String s : l.group(1).trim().split("\\s+")) if (!s.isEmpty()) out.add(s);
		return out;
	}

	public static Map<String, String> readValues(Path file) {
		Map<String, String> out = new LinkedHashMap<>();
		if (!Files.isRegularFile(file)) return out;
		Properties p = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(reader);
		} catch (IOException e) {
			return out;
		}
		for (String k : p.stringPropertyNames()) out.put(k, p.getProperty(k).trim());
		return out;
	}

	public Map<String, Option> options() {
		return Collections.unmodifiableMap(this.options);
	}

	public Map<String, String> values() {
		return Collections.unmodifiableMap(this.values);
	}

	public String value(String name) {
		Option o = this.options.get(name);
		String v = this.values.get(name);
		if (o == null) return v == null ? "" : v;
		return v != null && (o.bool() || o.values().isEmpty() || o.values().contains(v) || o.kind() == Kind.DEFINE || o.kind() == Kind.CONST) ? v : o.defaultValue();
	}

	public Map<String, String> macros() {
		Map<String, String> m = new LinkedHashMap<>();
		for (Option o : this.options.values()) {
			String v = value(o.name());
			if (o.kind() == Kind.BOOLEAN) {
				if (v.equals("true")) m.put(o.name(), "");
			} else if (o.kind() == Kind.DEFINE) {
				m.put(o.name(), v);
			}
		}
		return m;
	}

	public String apply(String text) {
		if (this.values.isEmpty()) return text;
		StringBuilder out = new StringBuilder(text.length() + 64);
		int start = 0;
		while (start <= text.length()) {
			int end = text.indexOf('\n', start);
			if (end < 0) end = text.length();
			String line = text.substring(start, end);
			out.append(applyLine(line));
			if (end < text.length()) out.append('\n');
			start = end + 1;
		}
		return out.toString();
	}

	private String applyLine(String line) {
		Option parsed = parse(line);
		if (parsed == null) return line;
		Option known = this.options.get(parsed.name());
		if (known == null || !this.values.containsKey(parsed.name())) return line;
		String v = value(parsed.name());
		String comment = parsed.comment().isEmpty() ? "" : " // " + parsed.comment();
		return switch (parsed.kind()) {
			case BOOLEAN -> (v.equals("true") ? "#define " : "//#define ") + parsed.name() + comment;
			case DEFINE -> "#define " + parsed.name() + " " + v + comment;
			case CONST -> {
				Matcher m = CONST.matcher(line);
				yield m.matches() ? "const " + m.group(1) + " " + parsed.name() + " = " + v + ";" + comment : line;
			}
		};
	}
}
