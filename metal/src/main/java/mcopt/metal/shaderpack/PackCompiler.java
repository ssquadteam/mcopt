package mcopt.metal.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class PackCompiler {
	public static final List<String> GBUFFERS = List.of("gbuffers_basic", "gbuffers_line", "gbuffers_textured", "gbuffers_textured_lit", "gbuffers_skybasic",
		"gbuffers_skytextured", "gbuffers_clouds", "gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout_mip", "gbuffers_terrain_cutout",
		"gbuffers_damagedblock", "gbuffers_block", "gbuffers_block_translucent", "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities",
		"gbuffers_entities_translucent", "gbuffers_lightning", "gbuffers_particles", "gbuffers_particles_translucent", "gbuffers_entities_glowing",
		"gbuffers_armor_glint", "gbuffers_spidereyes", "gbuffers_hand", "gbuffers_hand_water", "gbuffers_weather", "gbuffers_water");
	public static final List<String> SHADOWS = List.of("shadow", "shadow_solid", "shadow_cutout", "shadow_water", "shadow_entities", "shadow_lightning",
		"shadow_block");
	public static final Map<String, String> FALLBACK = Map.ofEntries(Map.entry("gbuffers_line", "gbuffers_basic"), Map.entry("gbuffers_textured", "gbuffers_basic"),
		Map.entry("gbuffers_textured_lit", "gbuffers_textured"), Map.entry("gbuffers_skybasic", "gbuffers_basic"),
		Map.entry("gbuffers_skytextured", "gbuffers_textured"), Map.entry("gbuffers_clouds", "gbuffers_textured"),
		Map.entry("gbuffers_terrain", "gbuffers_textured_lit"), Map.entry("gbuffers_terrain_solid", "gbuffers_terrain"),
		Map.entry("gbuffers_terrain_cutout_mip", "gbuffers_terrain"), Map.entry("gbuffers_terrain_cutout", "gbuffers_terrain"),
		Map.entry("gbuffers_damagedblock", "gbuffers_terrain"), Map.entry("gbuffers_block", "gbuffers_terrain"),
		Map.entry("gbuffers_block_translucent", "gbuffers_block"), Map.entry("gbuffers_beaconbeam", "gbuffers_textured"),
		Map.entry("gbuffers_item", "gbuffers_entities"), Map.entry("gbuffers_entities", "gbuffers_textured_lit"),
		Map.entry("gbuffers_entities_translucent", "gbuffers_entities"), Map.entry("gbuffers_lightning", "gbuffers_entities"),
		Map.entry("gbuffers_particles", "gbuffers_textured_lit"), Map.entry("gbuffers_particles_translucent", "gbuffers_particles"),
		Map.entry("gbuffers_entities_glowing", "gbuffers_entities"), Map.entry("gbuffers_armor_glint", "gbuffers_textured"),
		Map.entry("gbuffers_spidereyes", "gbuffers_textured"), Map.entry("gbuffers_hand", "gbuffers_textured_lit"),
		Map.entry("gbuffers_hand_water", "gbuffers_hand"), Map.entry("gbuffers_weather", "gbuffers_textured_lit"), Map.entry("gbuffers_water", "gbuffers_terrain"),
		Map.entry("shadow_solid", "shadow"), Map.entry("shadow_cutout", "shadow"), Map.entry("shadow_water", "shadow"), Map.entry("shadow_entities", "shadow"),
		Map.entry("shadow_lightning", "shadow_entities"), Map.entry("shadow_block", "shadow"));

	public record Stage(String path, GlslRewrite.Result result) {
	}

	public record Program(String name, String dimension, @Nullable Stage vertex, Stage fragment, @Nullable Stage compute, List<Integer> drawBuffers,
		Map<String, String> consts, Map<String, Integer> locations) {
	}

	public record Compiled(String program, String adapter, ShaderCompiler.Msl vertex, ShaderCompiler.Msl fragment, List<Integer> drawBuffers) {
	}

	private final PackSource source;
	private final PackOptions options;
	private final PackProperties properties;
	private final Map<String, String> macros;
	private final String dimension;
	private final Map<String, Object> programs = new ConcurrentHashMap<>();
	private final Map<String, Object> compiled = new ConcurrentHashMap<>();
	private final Map<String, GlslIncludes.Expanded> expanded = new ConcurrentHashMap<>();

	public PackCompiler(PackSource source, PackOptions options, PackProperties properties, Map<String, String> macros, String dimension) {
		this.source = source;
		this.options = options;
		this.properties = properties;
		this.macros = macros;
		this.dimension = dimension;
	}

	public static Map<String, String> macros(PackOptions options, PackMacros.Environment env) {
		Map<String, String> m = new LinkedHashMap<>(PackMacros.standard(env));
		m.putAll(options.macros());
		return m;
	}

	public static String dimensionFolder(PackSource source, String dimensionId, @Nullable PackProperties dimensionProperties) {
		if (dimensionProperties != null) {
			String wildcard = null;
			for (var e : dimensionProperties.entries().entrySet()) {
				if (!e.getKey().startsWith("dimension.")) continue;
				for (String id : e.getValue().trim().split("\\s+")) {
					if (id.equals(dimensionId)) return e.getKey().substring(10);
					if (id.equals("*") && wildcard == null) wildcard = e.getKey().substring(10);
				}
			}
			if (wildcard != null) return wildcard;
		}
		String folder = switch (dimensionId) {
			case "minecraft:overworld" -> "world0";
			case "minecraft:the_nether" -> "world-1";
			case "minecraft:the_end" -> "world1";
			default -> "";
		};
		if (!folder.isEmpty()) {
			for (String f : source.files()) if (f.startsWith(folder + "/")) return folder;
		}
		return "";
	}

	public PackSource source() {
		return this.source;
	}

	public PackProperties properties() {
		return this.properties;
	}

	public PackOptions options() {
		return this.options;
	}

	public String dimension() {
		return this.dimension;
	}

	public Map<String, String> macros() {
		return this.macros;
	}

	private @Nullable String file(String program, String extension) {
		String inDim = this.dimension.isEmpty() ? null : this.dimension + "/" + program + extension;
		if (inDim != null && this.source.exists(inDim)) return inDim;
		String root = program + extension;
		return this.source.exists(root) ? root : null;
	}

	public boolean exists(String program) {
		if (file(program, ".fsh") == null && file(program, ".csh") == null) return false;
		Boolean enabled = this.properties.programEnabled(this.dimension, program);
		return enabled == null || enabled;
	}

	public @Nullable String resolve(String program) {
		String p = program;
		while (p != null) {
			if (exists(p)) return p;
			p = FALLBACK.get(p);
		}
		return null;
	}

	public List<String> passes(String prefix) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			String name = i == 0 ? prefix : prefix + i;
			if (exists(name)) out.add(name);
		}
		return out;
	}

	public Program program(String name) throws IOException {
		Object cached = this.programs.computeIfAbsent(name, n -> {
			try {
				return load(n);
			} catch (IOException | RuntimeException e) {
				return e;
			}
		});
		if (cached instanceof IOException e) throw e;
		if (cached instanceof RuntimeException e) throw e;
		return (Program) cached;
	}

	private Stage stage(String path, ShaderCompiler.Kind kind) throws IOException {
		GlslIncludes.Expanded e = this.expanded.get(path);
		if (e == null) {
			e = GlslIncludes.expand(this.source, this.options, path);
			this.expanded.put(path, e);
		}
		Map<String, String> m = new LinkedHashMap<>(this.macros);
		m.putAll(PackMacros.stage(switch (kind) {
			case VERTEX -> "vertex";
			case FRAGMENT -> "fragment";
			case COMPUTE -> "compute";
		}));
		String pre = ShaderCompiler.preprocess("#version 450\n" + e.text(), kind, path, m);
		GlslRewrite.Stage s = switch (kind) {
			case VERTEX -> GlslRewrite.Stage.VERTEX;
			case FRAGMENT -> GlslRewrite.Stage.FRAGMENT;
			case COMPUTE -> GlslRewrite.Stage.COMPUTE;
		};
		try {
			return new Stage(path, GlslRewrite.rewrite(pre, s, e.version()));
		} catch (GlslRewrite.RewriteException ex) {
			throw new IOException(path + ": " + ex.getMessage(), ex);
		}
	}

	private Program load(String name) throws IOException {
		String compute = file(name, ".csh");
		String fsh = file(name, ".fsh");
		if (fsh == null && compute != null) {
			Stage c = stage(compute, ShaderCompiler.Kind.COMPUTE);
			return new Program(name, this.dimension, null, c, c, List.of(), GlslRewrite.constDirectives(c.result()), Map.of());
		}
		if (fsh == null) throw new IOException(name + " has no fragment shader");
		if (file(name, ".gsh") != null) throw new IOException(name + " needs a geometry shader, which Metal doesn't have");
		String vsh = file(name, ".vsh");
		Stage v = vsh == null ? null : stage(vsh, ShaderCompiler.Kind.VERTEX);
		Stage f = stage(fsh, ShaderCompiler.Kind.FRAGMENT);
		Map<String, Integer> locations = v == null ? Map.of() : ProgramAssembler.locations(v.result());
		return new Program(name, this.dimension, v, f, null, GlslRewrite.drawBuffers(f.result(), v == null ? null : v.result()),
			GlslRewrite.constDirectives(v == null ? null : v.result(), f.result()), locations);
	}

	public Compiled compile(String name, ProgramAssembler.Adapter adapter, ShaderCompiler.Bindings bindings) throws IOException {
		String key = name + "|" + adapter.key();
		Object cached = this.compiled.computeIfAbsent(key, k -> {
			try {
				return build(name, adapter, bindings);
			} catch (IOException | RuntimeException e) {
				return e;
			}
		});
		if (cached instanceof IOException e) throw e;
		if (cached instanceof RuntimeException e) throw e;
		return (Compiled) cached;
	}

	private Compiled build(String name, ProgramAssembler.Adapter adapter, ShaderCompiler.Bindings bindings) throws IOException {
		Program p = program(name);
		GlslRewrite.Result vr = p.vertex() == null ? defaultVertex() : p.vertex().result();
		Map<String, Integer> locations = p.vertex() == null ? ProgramAssembler.locations(vr) : p.locations();
		String vertex = ProgramAssembler.vertex(vr, locations, adapter);
		String fragment = ProgramAssembler.fragment(p.fragment().result(), locations, adapter, p.drawBuffers());
		String vName = (p.vertex() == null ? name + ".vsh" : p.vertex().path()) + " [" + adapter.key() + "]";
		String fName = p.fragment().path() + " [" + adapter.key() + "]";
		ShaderCompiler.Msl vm = ShaderCompiler.msl(spirv(vertex, ShaderCompiler.Kind.VERTEX, vName), ShaderCompiler.Kind.VERTEX, bindings);
		ShaderCompiler.Msl fm = ShaderCompiler.msl(spirv(fragment, ShaderCompiler.Kind.FRAGMENT, fName), ShaderCompiler.Kind.FRAGMENT, bindings);
		return new Compiled(name, adapter.key(), vm, fm, p.drawBuffers());
	}

	public ShaderCompiler.Msl compileCompute(String name, ShaderCompiler.Bindings bindings) throws IOException {
		Program p = program(name);
		if (p.compute() == null) throw new IOException(name + " isn't a compute program");
		String src = ProgramAssembler.compute(p.compute().result());
		return ShaderCompiler.msl(spirv(src, ShaderCompiler.Kind.COMPUTE, p.compute().path()), ShaderCompiler.Kind.COMPUTE, bindings);
	}

	public static volatile java.util.function.@Nullable BiConsumer<String, String> dump;

	private static int[] spirv(String source, ShaderCompiler.Kind kind, String name) {
		var d = dump;
		if (d != null) d.accept(name, source);
		return ShaderCompiler.spirv(source, kind, name);
	}

	private static GlslRewrite.Result defaultVertex() {
		String src = "varying vec2 texcoord;\nvoid main() {\n\tgl_Position = ftransform();\n\ttexcoord = gl_MultiTexCoord0.xy;\n}\n";
		return GlslRewrite.rewrite(src, GlslRewrite.Stage.VERTEX, 120);
	}
}
