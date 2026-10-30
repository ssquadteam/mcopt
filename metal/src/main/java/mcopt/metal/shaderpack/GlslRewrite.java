package mcopt.metal.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import mcopt.metal.shaderpack.GlslLexer.Kind;
import mcopt.metal.shaderpack.GlslLexer.Tok;

public final class GlslRewrite {
	public enum Stage {
		VERTEX, FRAGMENT, COMPUTE
	}

	public record Varying(String name, String type, int array, String qualifiers) {
		public int slots(Map<String, Integer> structs) {
			Integer struct = structs.get(this.type);
			if (struct != null) return struct * Math.max(1, this.array);
			return slots();
		}

		public int slots() {
			int per = switch (this.type) {
				case "float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4" -> 1;
				case "mat2", "mat2x2", "mat2x3", "mat2x4", "dmat2" -> 2;
				case "mat3", "mat3x2", "mat3x3", "mat3x4" -> 3;
				case "mat4", "mat4x2", "mat4x3", "mat4x4" -> 4;
				default -> 8;
			};
			return per * Math.max(1, this.array);
		}

		public String declaration(String storage, int location) {
			return "layout(location = " + location + ") " + (this.qualifiers.isEmpty() ? "" : this.qualifiers + " ") + storage + " " + this.type + " " + this.name
				+ (this.array > 0 ? "[" + this.array + "]" : "") + ";\n";
		}
	}

	public record FragOutput(String name, String type, int location) {
	}

	public static final class Result {
		public String body = "";
		public final Set<String> builtins = new LinkedHashSet<>();
		public final Map<String, String> attributes = new LinkedHashMap<>();
		public final Map<String, Integer> structs = new HashMap<>();
		public final List<Varying> inputs = new ArrayList<>();
		public final List<Varying> outputs = new ArrayList<>();
		public final List<FragOutput> fragOutputs = new ArrayList<>();
		public final List<String> pragmas = new ArrayList<>();
		public final Map<String, String> consts = new LinkedHashMap<>();
		public final Map<String, String> uniformDefaults = new LinkedHashMap<>();
		public final Map<String, String> samplers = new LinkedHashMap<>();
		public final Map<String, String> uniforms = new LinkedHashMap<>();
		public final Set<String> extensions = new LinkedHashSet<>();
		public String localSize = "";
	}

	public static final class RewriteException extends RuntimeException {
		public RewriteException(String message) {
			super(message);
		}
	}

	private static final Set<String> QUALIFIERS = Set.of("in", "out", "inout", "varying", "attribute", "uniform", "const", "flat", "smooth", "noperspective", "centroid",
		"invariant", "highp", "mediump", "lowp", "precise", "sample", "patch", "buffer", "shared", "readonly", "writeonly", "coherent", "volatile", "restrict");
	private static final Set<String> INTERPOLATION = Set.of("flat", "smooth", "noperspective", "centroid", "invariant", "sample");
	public static final Set<String> ATTRIBUTES = Set.of("mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock", "at_velocity", "vaPosition", "vaColor", "vaUV0",
		"vaUV1", "vaUV2", "vaNormal", "mc_chunkFade", "iris_Entity");
	public static final Map<String, String> PER_DRAW = Map.ofEntries(Map.entry("modelViewMatrix", "iris_ModelViewMat"),
		Map.entry("modelViewMatrixInverse", "iris_ModelViewMatInv"), Map.entry("projectionMatrix", "iris_ProjMat"),
		Map.entry("projectionMatrixInverse", "iris_ProjMatInv"), Map.entry("normalMatrix", "iris_NormalMat"), Map.entry("textureMatrix", "iris_TextureMat0"),
		Map.entry("chunkOffset", "iris_ChunkOffset"), Map.entry("iris_ModelViewMat", "iris_ModelViewMat"), Map.entry("iris_ProjMat", "iris_ProjMat"),
		Map.entry("iris_ModelViewMatInverse", "iris_ModelViewMatInv"), Map.entry("iris_ProjMatInverse", "iris_ProjMatInv"),
		Map.entry("iris_NormalMat", "iris_NormalMat"), Map.entry("iris_ChunkOffset", "iris_ChunkOffset"), Map.entry("iris_TextureMat", "iris_TextureMat0"),
		Map.entry("iris_ColorModulator", "iris_ColorModulator"), Map.entry("iris_currentAlphaTest", "iris_AlphaRef"));
	private static final Map<String, String> LEGACY = Map.ofEntries(Map.entry("gl_ModelViewMatrix", "iris_ModelViewMat"),
		Map.entry("gl_ModelViewMatrixInverse", "iris_ModelViewMatInv"), Map.entry("gl_ProjectionMatrix", "iris_ProjMat"),
		Map.entry("gl_ProjectionMatrixInverse", "iris_ProjMatInv"), Map.entry("gl_ModelViewProjectionMatrix", "iris_MVP"),
		Map.entry("gl_ModelViewProjectionMatrixInverse", "iris_MVPInv"), Map.entry("gl_NormalMatrix", "iris_NormalMat"),
		Map.entry("gl_ModelViewMatrixTranspose", "iris_ModelViewMatT"), Map.entry("gl_ProjectionMatrixTranspose", "iris_ProjMatT"),
		Map.entry("gl_ModelViewMatrixInverseTranspose", "iris_ModelViewMatInvT"), Map.entry("gl_ProjectionMatrixInverseTranspose", "iris_ProjMatInvT"),
		Map.entry("gl_Fog", "iris_Fog"), Map.entry("gl_ClipVertex", "iris_ClipVertex"));
	private static final Map<String, String> VERTEX_INPUTS = Map.of("gl_Vertex", "iris_Vertex", "gl_Color", "iris_Color", "gl_Normal", "iris_Normal",
		"gl_MultiTexCoord0", "iris_MultiTexCoord0", "gl_MultiTexCoord1", "iris_MultiTexCoord1", "gl_MultiTexCoord2", "iris_MultiTexCoord2", "gl_MultiTexCoord3",
		"iris_MultiTexCoord3", "gl_SecondaryColor", "iris_Color");
	private static final Map<String, String> FUNCTIONS = Map.ofEntries(Map.entry("texture1D", "texture"), Map.entry("texture2D", "texture"),
		Map.entry("texture3D", "texture"), Map.entry("textureCube", "texture"), Map.entry("texture2DArray", "texture"), Map.entry("texture2DRect", "texture"),
		Map.entry("texture1DLod", "textureLod"), Map.entry("texture2DLod", "textureLod"), Map.entry("texture3DLod", "textureLod"),
		Map.entry("textureCubeLod", "textureLod"), Map.entry("texture2DLodEXT", "textureLod"), Map.entry("texture2DLodARB", "textureLod"),
		Map.entry("texture2DArrayLod", "textureLod"), Map.entry("texture2DProj", "textureProj"), Map.entry("texture3DProj", "textureProj"),
		Map.entry("texture2DProjLod", "textureProjLod"), Map.entry("texture2DGrad", "textureGrad"), Map.entry("texture2DGradARB", "textureGrad"),
		Map.entry("texture2DGradEXT", "textureGrad"), Map.entry("texture3DGrad", "textureGrad"), Map.entry("texture3DGradARB", "textureGrad"),
		Map.entry("textureCubeGrad", "textureGrad"), Map.entry("texelFetch2D", "texelFetch"), Map.entry("texelFetch3D", "texelFetch"),
		Map.entry("texelFetch2DArray", "texelFetch"), Map.entry("textureSize2D", "textureSize"), Map.entry("textureSize3D", "textureSize"),
		Map.entry("shadow2D", "iris_shadow2D"), Map.entry("shadow2DLod", "iris_shadow2DLod"), Map.entry("shadow2DProj", "iris_shadow2DProj"),
		Map.entry("shadow2DProjLod", "iris_shadow2DProjLod"), Map.entry("textureGather2D", "textureGather"), Map.entry("texture2DGather", "textureGather"),
		Map.entry("ftransform", "iris_ftransform"));
	private static final Map<String, Integer> KEYWORDS_SINCE = Map.ofEntries(Map.entry("sample", 400), Map.entry("patch", 400), Map.entry("subroutine", 400),
		Map.entry("precise", 400), Map.entry("coherent", 420), Map.entry("volatile", 420), Map.entry("restrict", 420), Map.entry("readonly", 420),
		Map.entry("writeonly", 420), Map.entry("buffer", 430), Map.entry("shared", 430), Map.entry("sampler", 9999), Map.entry("samplerShadow", 9999),
		Map.entry("subpassInput", 9999));
	private static final Set<String> MSL_RESERVED = Set.of("new", "delete", "this", "class", "template", "namespace", "private", "public", "protected", "operator",
		"typename", "using", "virtual", "friend", "explicit", "mutable", "register", "signed", "unsigned", "char", "short", "long", "goto", "union", "enum", "sizeof",
		"static", "extern", "constexpr", "nullptr", "thread", "device", "constant", "threadgroup", "kernel", "vertex", "fragment", "half", "typedef", "auto", "try",
		"catch", "throw", "export", "alignas", "alignof", "decltype", "noexcept", "static_assert", "thread_local", "wchar_t", "assert", "metal", "simd", "ray_data",
		"object_data", "vec", "array", "texture2d", "sampler2d", "uchar", "ushort", "ulong", "size_t", "ptrdiff_t", "float2", "float3", "float4", "int2", "int3",
		"int4", "uint2", "uint3", "uint4", "half2", "half3", "half4", "float2x2", "float3x3", "float4x4", "atomic", "visible", "stage_in", "INFINITY", "NAN",
		"M_PI", "M_PI_F", "MAXFLOAT", "FLT_MAX", "FLT_MIN", "FLT_EPSILON", "HUGE_VALF", "quad", "abs_", "select", "fmin", "fmax", "fmod_", "rint", "copysign",
		"fdim", "sincos", "bool2", "bool3", "bool4", "packed_float3");
	private static final Set<String> TYPES = Set.of("float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
		"bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "mat2x2", "mat3x3", "mat4x4", "mat2x3", "mat3x2", "mat2x4", "mat4x2", "mat3x4", "mat4x3");
	private GlslRewrite() {
	}

	public static Result rewrite(String preprocessed, Stage stage, int version) {
		Result r = new Result();
		StringBuilder code = new StringBuilder(preprocessed.length());
		int start = 0, n = preprocessed.length();
		while (start < n) {
			int end = preprocessed.indexOf('\n', start);
			if (end < 0) end = n;
			String line = preprocessed.substring(start, end);
			String t = line.trim();
			if (t.startsWith("#")) {
				String d = t.substring(1).trim();
				if (d.startsWith("pragma")) {
					String p = d.substring(6).trim();
					if (p.startsWith(GlslIncludes.PRAGMA)) r.pragmas.add(GlslIncludes.decode(p.substring(GlslIncludes.PRAGMA.length()).trim()));
				} else if (d.startsWith("extension")) {
					String ext = d.substring(9).trim();
					int colon = ext.indexOf(':');
					if (colon > 0) r.extensions.add(ext.substring(0, colon).trim());
				}
				code.append('\n');
			} else {
				code.append(line).append('\n');
			}
			start = end + 1;
		}
		List<Tok> toks = GlslLexer.lex(code.toString());
		renameReserved(toks, version);
		Map<String, String> renames = new HashMap<>();
		List<Tok> out = new ArrayList<>(toks.size());
		int i = 0;
		Set<String> userFunctions = new LinkedHashSet<>();
		while (i < toks.size()) {
			int end = statementEnd(toks, i);
			List<Tok> stmt = toks.subList(i, end);
			globalStatement(stmt, stage, r, renames, userFunctions, out);
			i = end;
		}
		for (String f : userFunctions) if (!f.startsWith("iris_")) renames.put(f, "iris_u_" + f);
		for (String st : new ArrayList<>(r.structs.keySet())) {
			renames.put(st, "iris_s_" + st);
			r.structs.put("iris_s_" + st, r.structs.get(st));
		}
		Set<String> declared = new java.util.HashSet<>();
		for (int k = 0; k < out.size(); k++) {
			Tok t = out.get(k);
			if (!t.ident() || !PER_DRAW.containsKey(t.text)) continue;
			int p = GlslLexer.prev(out, k);
			if (p >= 0 && (TYPES.contains(out.get(p).text) || out.get(p).is(","))) declared.add(t.text);
		}
		for (var e : PER_DRAW.entrySet()) {
			if (declared.contains(e.getKey()) || renames.containsKey(e.getKey())) continue;
			renames.put(e.getKey(), e.getValue());
		}
		rewriteBody(out, stage, r, renames);
		r.body = GlslLexer.join(out);
		r.inputs.replaceAll(v -> renames.containsKey(v.type()) ? new Varying(v.name(), renames.get(v.type()), v.array(), v.qualifiers()) : v);
		r.outputs.replaceAll(v -> renames.containsKey(v.type()) ? new Varying(v.name(), renames.get(v.type()), v.array(), v.qualifiers()) : v);
		r.attributes.replaceAll((k, v) -> renames.getOrDefault(v, v));
		return r;
	}

	private static void renameReserved(List<Tok> toks, int version) {
		boolean textureSampler = false;
		for (int k = 0; k < toks.size(); k++) {
			Tok t = toks.get(k);
			if (!t.ident()) continue;
			if (t.is("texture")) {
				int p = GlslLexer.prev(toks, k), q = GlslLexer.next(toks, k);
				if (p >= 0 && toks.get(p).ident() && toks.get(p).text.contains("sampler") && q < toks.size() && toks.get(q).is(";")) textureSampler = true;
			}
			if (MSL_RESERVED.contains(t.text)) {
				t.text = "iris_k_" + t.text;
				continue;
			}
			Integer since = KEYWORDS_SINCE.get(t.text);
			if (since != null && version < since) {
				int q = GlslLexer.next(toks, k);
				boolean qualifier = q < toks.size() && toks.get(q).ident() && !t.is("sampler") && !t.is("samplerShadow");
				if (!qualifier) t.text = "iris_r_" + t.text;
			}
		}
		if (textureSampler || version < 130) {
			for (int k = 0; k < toks.size(); k++) {
				Tok t = toks.get(k);
				if (!t.is("texture")) continue;
				int q = GlslLexer.next(toks, k);
				if (q < toks.size() && toks.get(q).is("(") && version >= 130 && !textureSampler) continue;
				if (q < toks.size() && toks.get(q).is("(") && !textureSampler) continue;
				t.text = "iris_texture";
			}
		}
	}

	private static int statementEnd(List<Tok> toks, int start) {
		int depth = 0, paren = 0;
		boolean sawParenBeforeBrace = false, braceOpened = false;
		for (int k = start; k < toks.size(); k++) {
			Tok t = toks.get(k);
			if (t.kind != Kind.PUNCT) continue;
			switch (t.text) {
				case "(" -> paren++;
				case ")" -> {
					paren--;
					if (depth == 0 && !braceOpened) sawParenBeforeBrace = true;
				}
				case "{" -> {
					if (depth == 0 && !braceOpened) braceOpened = true;
					depth++;
				}
				case "}" -> {
					depth--;
					if (depth == 0 && sawParenBeforeBrace) return k + 1;
				}
				case ";" -> {
					if (depth == 0 && paren == 0) return k + 1;
				}
				default -> {
				}
			}
		}
		return toks.size();
	}

	private static void globalStatement(List<Tok> stmt, Stage stage, Result r, Map<String, String> renames, Set<String> userFunctions, List<Tok> out) {
		List<Integer> idx = new ArrayList<>();
		for (int k = 0; k < stmt.size(); k++) if (!stmt.get(k).space()) idx.add(k);
		if (idx.isEmpty()) {
			out.addAll(stmt);
			return;
		}
		int firstBrace = -1, firstParen = -1;
		for (int k : idx) {
			Tok t = stmt.get(k);
			if (firstBrace < 0 && t.is("{")) firstBrace = k;
			if (firstParen < 0 && t.is("(")) firstParen = k;
		}
		if (stmt.get(idx.getFirst()).is("struct") && idx.size() > 2 && firstBrace > 0) {
			int close = GlslLexer.match(stmt, firstBrace);
			int slots = 0;
			List<Tok> body = stmt.subList(firstBrace + 1, close);
			for (int k = 0; k < body.size(); k++) {
				Tok t = body.get(k);
				if (!t.is(";")) continue;
				int q = k - 1;
				while (q >= 0 && !body.get(q).is(";")) q--;
				String member = GlslLexer.join(body.subList(q + 1, k)).trim();
				String[] parts = member.split("\\s+");
				int each = parts.length > 0 ? new Varying("", parts[0], 0, "").slots(r.structs) : 1;
				int count = member.split(",").length;
				int bracket = member.indexOf('[');
				int arr = bracket > 0 ? Math.max(1, arraySize(member.substring(bracket + 1, member.indexOf(']', bracket)))) : 1;
				slots += each * count * arr;
			}
			r.structs.put(stmt.get(idx.get(1)).text, Math.max(1, slots));
		}
		boolean function = firstParen >= 0 && firstBrace > firstParen && !stmt.get(idx.getFirst()).is("layout") && !isStorage(stmt.get(idx.getFirst()).text);
		if (function) {
			int nameAt = GlslLexer.prev(stmt, firstParen);
			if (nameAt >= 0 && stmt.get(nameAt).ident()) {
				String name = stmt.get(nameAt).text;
				if (name.equals("main")) stmt.get(nameAt).text = "iris_main";
				else userFunctions.add(name);
			}
			out.addAll(stmt);
			return;
		}
		if (firstParen >= 0 && firstBrace < 0 && stmt.get(idx.getLast()).is(";")) {
			int nameAt = GlslLexer.prev(stmt, firstParen);
			boolean prototype = nameAt >= 1 && stmt.get(nameAt).ident() && !isStorage(stmt.get(idx.getFirst()).text) && !stmt.get(idx.getFirst()).is("layout");
			if (prototype && GlslLexer.prev(stmt, nameAt) >= 0 && stmt.get(GlslLexer.prev(stmt, nameAt)).ident()) {
				String name = stmt.get(nameAt).text;
				if (name.equals("main")) stmt.get(nameAt).text = "iris_main";
				else userFunctions.add(name);
				out.addAll(stmt);
				return;
			}
		}
		String layout = "";
		int at = 0;
		if (stmt.get(idx.get(0)).is("layout")) {
			int open = idx.get(1);
			int close = GlslLexer.match(stmt, open);
			layout = GlslLexer.join(stmt.subList(idx.get(0), close + 1));
			while (at < idx.size() && idx.get(at) <= close) at++;
		}
		Set<String> quals = new LinkedHashSet<>();
		while (at < idx.size() && QUALIFIERS.contains(stmt.get(idx.get(at)).text)) quals.add(stmt.get(idx.get(at++)).text);
		if (layout.contains("local_size") && quals.contains("in")) {
			r.localSize = layout;
			return;
		}
		if (at >= idx.size()) {
			out.addAll(stmt);
			return;
		}
		boolean block = at + 1 < idx.size() && stmt.get(idx.get(at + 1)).is("{");
		boolean isIn = quals.contains("in") || quals.contains("attribute") || (quals.contains("varying") && stage == Stage.FRAGMENT);
		boolean isOut = quals.contains("out") || (quals.contains("varying") && stage == Stage.VERTEX);
		if (stage == Stage.COMPUTE || block || (!isIn && !isOut && !quals.contains("uniform") && !quals.contains("const"))) {
			if (quals.contains("const")) consts(stmt, idx, at, r);
			out.addAll(stmt);
			return;
		}
		if (quals.contains("const")) {
			consts(stmt, idx, at, r);
			out.addAll(stmt);
			return;
		}
		String type = stmt.get(idx.get(at)).text;
		int k = at + 1;
		int typeArray = 0;
		if (k < idx.size() && stmt.get(idx.get(k)).is("[")) {
			int close = GlslLexer.match(stmt, idx.get(k));
			typeArray = arraySize(GlslLexer.join(stmt.subList(idx.get(k) + 1, close)));
			while (k < idx.size() && idx.get(k) <= close) k++;
		}
		List<String[]> names = new ArrayList<>();
		while (k < idx.size()) {
			Tok nameTok = stmt.get(idx.get(k));
			if (!nameTok.ident()) break;
			String name = nameTok.text;
			k++;
			int array = typeArray;
			if (k < idx.size() && stmt.get(idx.get(k)).is("[")) {
				int close = GlslLexer.match(stmt, idx.get(k));
				array = arraySize(GlslLexer.join(stmt.subList(idx.get(k) + 1, close)));
				if (array == 0) array = -1;
				while (k < idx.size() && idx.get(k) <= close) k++;
			}
			String init = null;
			if (k < idx.size() && stmt.get(idx.get(k)).is("=")) {
				int from = idx.get(k) + 1, depth = 0, e = from;
				for (; e < stmt.size(); e++) {
					Tok t = stmt.get(e);
					if (t.is("(") || t.is("[") || t.is("{")) depth++;
					else if (t.is(")") || t.is("]") || t.is("}")) depth--;
					else if (depth == 0 && (t.is(",") || t.is(";"))) break;
				}
				init = GlslLexer.join(stmt.subList(from, e)).trim();
				while (k < idx.size() && idx.get(k) < e) k++;
			}
			names.add(new String[] {name, Integer.toString(array), init});
			if (k < idx.size() && stmt.get(idx.get(k)).is(",")) k++;
			else break;
		}
		StringBuilder interp = new StringBuilder();
		for (String q : quals) if (INTERPOLATION.contains(q)) interp.append(interp.isEmpty() ? "" : " ").append(q);
		StringBuilder replacement = new StringBuilder();
		if (quals.contains("uniform")) {
			boolean opaque = type.contains("sampler") || type.contains("image") || type.equals("atomic_uint");
			for (String[] nm : names) {
				String name = nm[0];
				if (!opaque && PER_DRAW.containsKey(name)) {
					renames.put(name, PER_DRAW.get(name));
					r.builtins.add(PER_DRAW.get(name));
					continue;
				}
				if (opaque) r.samplers.put(name, type);
				else r.uniforms.put(name, type);
				if (nm[2] != null) r.uniformDefaults.put(name, nm[2]);
				int arr = Integer.parseInt(nm[1]);
				replacement.append(layout.isEmpty() ? "" : layout + " ").append("uniform ").append(precisionless(quals)).append(type).append(' ').append(name)
					.append(arr > 0 ? "[" + arr + "]" : "").append(";\n");
			}
			out.add(new Tok(Kind.SPACE, replacement.toString()));
			return;
		}
		if (stage == Stage.VERTEX && isIn) {
			for (String[] nm : names) {
				String name = nm[0];
				if (ATTRIBUTES.contains(name)) r.attributes.put(name, type);
				else r.attributes.put(name, type);
			}
			return;
		}
		if (stage == Stage.FRAGMENT && isOut) {
			int location = -1;
			int loc = layout.indexOf("location");
			if (loc >= 0) {
				String rest = layout.substring(loc + 8).replaceAll("[^0-9].*$", "").trim();
				String digits = layout.substring(loc + 8).replaceAll("^[^0-9]*", "").replaceAll("[^0-9].*$", "");
				if (!digits.isEmpty()) location = Integer.parseInt(digits);
				else if (!rest.isEmpty()) location = Integer.parseInt(rest);
			}
			for (String[] nm : names) {
				int l = location >= 0 ? location : r.fragOutputs.size();
				r.fragOutputs.add(new FragOutput(nm[0], type, l));
				if (location >= 0) location++;
			}
			return;
		}
		StringBuilder markers = new StringBuilder();
		for (String[] nm : names) {
			int arr = Integer.parseInt(nm[1]);
			Varying v = new Varying(nm[0], type, Math.max(arr, 0), interp.toString());
			if (isIn) r.inputs.add(v);
			else r.outputs.add(v);
			markers.append(MARK).append(nm[0]).append(MARK).append('\n');
		}
		out.add(new Tok(Kind.SPACE, markers.toString()));
	}

	public static final char MARK = '\u0001';
	private static final Map<String, String> IMPLICIT_ATTRIBUTES = Map.of("vaPosition", "vec3", "vaColor", "vec4", "vaUV0", "vec2", "vaUV1", "ivec2", "vaUV2", "ivec2",
		"vaNormal", "vec3");

	private static String precisionless(Set<String> quals) {
		StringBuilder b = new StringBuilder();
		for (String q : quals) {
			if (q.equals("highp") || q.equals("mediump") || q.equals("lowp") || q.equals("readonly") || q.equals("writeonly") || q.equals("coherent")
				|| q.equals("volatile") || q.equals("restrict")) {
				b.append(q).append(' ');
			}
		}
		return b.toString();
	}

	private static boolean isStorage(String s) {
		return s.equals("uniform") || s.equals("in") || s.equals("out") || s.equals("varying") || s.equals("attribute") || s.equals("buffer") || s.equals("shared")
			|| s.equals("const") || s.equals("struct");
	}

	private static void consts(List<Tok> stmt, List<Integer> idx, int at, Result r) {
		if (at + 3 >= idx.size()) return;
		Tok type = stmt.get(idx.get(at)), name = stmt.get(idx.get(at + 1)), eq = stmt.get(idx.get(at + 2));
		if (!type.ident() || !name.ident() || !eq.is("=")) return;
		int from = idx.get(at + 2) + 1, to = stmt.size();
		for (int e = from; e < stmt.size(); e++) {
			if (stmt.get(e).is(";") || stmt.get(e).is(",")) {
				to = e;
				break;
			}
		}
		r.consts.put(name.text, GlslLexer.join(stmt.subList(from, to)).trim());
	}

	private static int arraySize(String s) {
		try {
			return Integer.parseInt(s.trim().replaceAll("[uU]$", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static void rewriteBody(List<Tok> toks, Stage stage, Result r, Map<String, String> renames) {
		for (int k = 0; k < toks.size(); k++) {
			Tok t = toks.get(k);
			if (!t.ident()) continue;
			String s = t.text;
			String renamed = renames.get(s);
			if (renamed != null) {
				t.text = renamed;
				if (renamed.startsWith("iris_") && !renamed.startsWith("iris_u_")) r.builtins.add(renamed);
				continue;
			}
			if (!s.startsWith("gl_") && FUNCTIONS.get(s) == null && !IMPLICIT_ATTRIBUTES.containsKey(s)) continue;
			String f = FUNCTIONS.get(s);
			if (f != null) {
				int q = GlslLexer.next(toks, k);
				if (q < toks.size() && toks.get(q).is("(")) {
					t.text = f;
					if (f.startsWith("iris_")) r.builtins.add(f);
				}
				continue;
			}
			String legacy = LEGACY.get(s);
			if (legacy != null) {
				t.text = legacy;
				r.builtins.add(legacy);
				continue;
			}
			if (stage == Stage.VERTEX && IMPLICIT_ATTRIBUTES.containsKey(s) && !r.attributes.containsKey(s)) r.attributes.put(s, IMPLICIT_ATTRIBUTES.get(s));
			if (stage == Stage.VERTEX) {
				String in = VERTEX_INPUTS.get(s);
				if (in != null) {
					t.text = in;
					r.builtins.add(in);
					continue;
				}
				if (s.equals("gl_FrontColor") || s.equals("gl_BackColor") || s.equals("gl_FrontSecondaryColor") || s.equals("gl_BackSecondaryColor")) {
					t.text = s.contains("Secondary") ? "iris_FrontSecondaryColor" : "iris_FrontColor";
					addVarying(r.outputs, new Varying(t.text, "vec4", 0, ""));
					continue;
				}
			} else if (stage == Stage.FRAGMENT) {
				if (s.equals("gl_Color")) {
					t.text = "iris_FrontColor";
					addVarying(r.inputs, new Varying("iris_FrontColor", "vec4", 0, ""));
					continue;
				}
				if (s.equals("gl_SecondaryColor")) {
					t.text = "iris_FrontSecondaryColor";
					addVarying(r.inputs, new Varying("iris_FrontSecondaryColor", "vec4", 0, ""));
					continue;
				}
				if (s.equals("gl_FragColor")) {
					t.text = "iris_FragData0";
					addFragData(r, 0);
					continue;
				}
				if (s.equals("gl_FragData")) {
					int index = constantIndex(toks, k);
					if (index < 0) {
						t.text = "iris_FragData";
						r.builtins.add("iris_FragDataArray");
						continue;
					}
					t.text = "iris_FragData" + index;
					addFragData(r, index);
					continue;
				}
			}
			if (s.equals("gl_FogFragCoord")) {
				t.text = "iris_FogFragCoord";
				addVarying(stage == Stage.VERTEX ? r.outputs : r.inputs, new Varying("iris_FogFragCoord", "float", 0, ""));
				continue;
			}
			if (s.equals("gl_TexCoord")) {
				int index = constantIndex(toks, k);
				if (index < 0) throw new RewriteException("gl_TexCoord with a non-constant index");
				t.text = "iris_TexCoord" + index;
				addVarying(stage == Stage.VERTEX ? r.outputs : r.inputs, new Varying(t.text, "vec4", 0, ""));
				continue;
			}
			if (s.equals("gl_TextureMatrix")) {
				int index = constantIndex(toks, k);
				t.text = "iris_TextureMat" + Math.max(0, Math.min(1, index));
				r.builtins.add(t.text);
			}
		}
	}

	private static int constantIndex(List<Tok> toks, int k) {
		int open = GlslLexer.next(toks, k);
		if (open >= toks.size() || !toks.get(open).is("[")) return -1;
		int num = GlslLexer.next(toks, open);
		int close = GlslLexer.next(toks, num);
		if (close >= toks.size() || !toks.get(close).is("]") || toks.get(num).kind != Kind.NUMBER) return -1;
		int index;
		try {
			index = Integer.parseInt(toks.get(num).text.replaceAll("[uU]$", ""));
		} catch (NumberFormatException e) {
			return -1;
		}
		for (int j = k + 1; j <= close; j++) toks.get(j).text = "";
		return index;
	}

	private static void addFragData(Result r, int index) {
		for (FragOutput o : r.fragOutputs) if (o.name().equals("iris_FragData" + index)) return;
		r.fragOutputs.add(new FragOutput("iris_FragData" + index, "vec4", index));
	}

	private static void addVarying(List<Varying> list, Varying v) {
		for (Varying x : list) if (x.name().equals(v.name())) return;
		list.add(v);
	}

	public static List<Integer> drawBuffers(Result fragment, Result vertex) {
		List<Integer> found = null;
		for (Result r : new Result[] {vertex, fragment}) {
			if (r == null) continue;
			for (String p : r.pragmas) {
				if (p.startsWith("RENDERTARGETS:")) {
					List<Integer> l = new ArrayList<>();
					for (String s : p.substring(14).split(",")) if (!s.isBlank()) l.add(Integer.parseInt(s.trim()));
					found = l;
				} else if (p.startsWith("DRAWBUFFERS:")) {
					List<Integer> l = new ArrayList<>();
					for (char c : p.substring(12).toCharArray()) if (Character.isDigit(c)) l.add(c - '0');
					found = l;
				}
			}
		}
		return found == null ? List.of(0) : found;
	}

	public static Map<String, String> constDirectives(Result... stages) {
		Map<String, String> out = new TreeMap<>();
		for (Result r : stages) {
			if (r == null) continue;
			for (String p : r.pragmas) {
				if (!p.startsWith("const")) continue;
				String body = p.substring(5).trim();
				int sp = body.indexOf(' ');
				int eq = body.indexOf('=');
				if (sp < 0 || eq < 0) continue;
				out.put(body.substring(sp + 1, eq).trim(), body.substring(eq + 1).replace(";", "").trim());
			}
			out.putAll(r.consts);
		}
		return out;
	}
}
