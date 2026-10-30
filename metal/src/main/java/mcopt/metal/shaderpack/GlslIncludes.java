package mcopt.metal.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GlslIncludes {
	public static final String PRAGMA = "iris_";

	public record Expanded(String text, int version, String profile) {
	}

	private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+[\"<]([^\">]+)[\">]");
	private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+(\\d+)\\s*(\\w*)");
	private static final Pattern DIRECTIVE = Pattern.compile("(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9 ,]*[0-9])");
	private static final Pattern CONST = Pattern.compile("const\\s+(?:int|float|bool|vec2|vec3|vec4|ivec2|ivec3)\\s+\\w+\\s*=\\s*[^;]+;");

	private GlslIncludes() {
	}

	public static Expanded expand(PackSource source, PackOptions options, String path) throws IOException {
		StringBuilder out = new StringBuilder(64 * 1024);
		int[] version = {-1};
		String[] profile = {""};
		include(source, options, path, out, new ArrayDeque<>(), version, profile);
		String text = encodeDirectives(out.toString());
		return new Expanded(text, version[0] < 0 ? 120 : version[0], profile[0]);
	}

	private static void include(PackSource source, PackOptions options, String path, StringBuilder out, Deque<String> stack, int[] version, String[] profile)
		throws IOException {
		if (stack.contains(path)) throw new IOException("include cycle: " + String.join(" -> ", stack) + " -> " + path);
		String text = source.read(path);
		if (text == null) throw new IOException((stack.isEmpty() ? "" : stack.peek() + ": ") + "missing " + path);
		text = options.apply(text);
		stack.push(path);
		int start = 0, n = text.length();
		while (start < n) {
			int end = text.indexOf('\n', start);
			if (end < 0) end = n;
			String line = text.substring(start, end);
			if (line.indexOf('#') >= 0) {
				Matcher inc = INCLUDE.matcher(line);
				if (inc.find()) {
					include(source, options, PackSource.resolve(path, inc.group(1)), out, stack, version, profile);
					out.append('\n');
					start = end + 1;
					continue;
				}
				Matcher v = VERSION.matcher(line);
				if (v.find()) {
					if (version[0] < 0) {
						version[0] = Integer.parseInt(v.group(1));
						profile[0] = v.group(2);
					}
					out.append('\n');
					start = end + 1;
					continue;
				}
			}
			out.append(line).append('\n');
			start = end + 1;
		}
		stack.pop();
	}

	static String encodeDirectives(String text) {
		StringBuilder out = new StringBuilder(text.length() + 256);
		StringBuilder pending = new StringBuilder();
		int i = 0, n = text.length();
		boolean lineStart = true;
		while (i < n) {
			char c = text.charAt(i);
			if (c == '/' && i + 1 < n && (text.charAt(i + 1) == '*' || text.charAt(i + 1) == '/')) {
				boolean block = text.charAt(i + 1) == '*';
				int end = block ? text.indexOf("*/", i + 2) : text.indexOf('\n', i + 2);
				if (end < 0) end = n;
				String body = text.substring(i + 2, Math.min(end, n));
				collect(body, pending);
				int next = block ? Math.min(n, end + 2) : end;
				if (block) {
					for (int k = i; k < next; k++) if (text.charAt(k) == '\n') out.append('\n');
					out.append(' ');
				}
				i = next;
				continue;
			}
			if (c == '"') {
				int end = text.indexOf('"', i + 1);
				if (end < 0) end = n - 1;
				out.append(text, i, end + 1);
				i = end + 1;
				continue;
			}
			out.append(c);
			if (c == '\n') {
				if (!pending.isEmpty() && !continued(out)) {
					out.append(pending);
					pending.setLength(0);
				}
				lineStart = true;
			} else if (!Character.isWhitespace(c)) {
				lineStart = false;
			}
			i++;
		}
		if (!pending.isEmpty()) out.append('\n').append(pending);
		return out.toString();
	}

	private static boolean continued(StringBuilder out) {
		int k = out.length() - 2;
		while (k >= 0 && (out.charAt(k) == ' ' || out.charAt(k) == '\t' || out.charAt(k) == '\r')) k--;
		return k >= 0 && out.charAt(k) == '\\';
	}

	private static void collect(String comment, StringBuilder pending) {
		Matcher d = DIRECTIVE.matcher(comment);
		while (d.find()) pending.append(pragma(d.group(1) + ":" + d.group(2).replace(" ", "")));
		if (comment.contains("const")) {
			Matcher c = CONST.matcher(comment);
			while (c.find()) pending.append(pragma(c.group()));
		}
	}

	private static String pragma(String payload) {
		return "#pragma " + PRAGMA + HexFormat.of().formatHex(payload.getBytes(StandardCharsets.UTF_8)) + "\n";
	}

	public static String decode(String hex) {
		return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
	}
}
