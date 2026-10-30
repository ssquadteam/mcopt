import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mcopt.metal.shaderpack.Adapters;
import mcopt.metal.shaderpack.PackCompiler;
import mcopt.metal.shaderpack.PackMacros;
import mcopt.metal.shaderpack.PackOptions;
import mcopt.metal.shaderpack.PackProperties;
import mcopt.metal.shaderpack.PackSamplers;
import mcopt.metal.shaderpack.PackSource;
import mcopt.metal.shaderpack.ProgramAssembler;
import mcopt.metal.shaderpack.ShaderCompiler;

public class Check {
	public static void main(String[] args) throws Exception {
		Path out = Path.of(args[0]);
		String dimension = System.getProperty("dim", "minecraft:overworld");
		for (int a = 1; a < args.length; a++) {
			Path packPath = Path.of(args[a]);
			long start = System.nanoTime();
			PackSource source = PackSource.open(packPath);
			Path values = Path.of(args[a].replaceAll("/$", "") + ".txt");
			PackOptions options = PackOptions.discover(source, PackOptions.readValues(values));
			Map<String, String> macros = PackCompiler.macros(options, PackMacros.Environment.DEFAULT);
			PackProperties props = PackProperties.parse(source.read("shaders.properties"), macros);
			PackProperties dims = source.exists("dimension.properties") ? PackProperties.parse(source.read("dimension.properties"), macros) : null;
			String folder = PackCompiler.dimensionFolder(source, dimension, dims);
			PackCompiler compiler = new PackCompiler(source, options, props, PackMacros.standard(PackMacros.Environment.DEFAULT), folder);
			List<String> programs = new ArrayList<>();
			for (String p : PackCompiler.GBUFFERS) if (compiler.exists(p)) programs.add(p);
			for (String p : PackCompiler.SHADOWS) if (compiler.exists(p)) programs.add(p);
			for (String prefix : List.of("setup", "begin", "shadowcomp", "prepare", "deferred", "composite")) programs.addAll(compiler.passes(prefix));
			if (compiler.exists("final")) programs.add("final");
			Path dir = out.resolve(source.name().replaceAll("[^A-Za-z0-9_.-]", "_"));
			Files.createDirectories(dir);
			ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
			List<Path> msl = new ArrayList<>();
			AtomicInteger ok = new AtomicInteger();
			if (Boolean.getBoolean("dump")) {
				PackCompiler.dump = (name, src) -> {
					try {
						Files.writeString(dir.resolve(name.replaceAll("[^A-Za-z0-9_.-]", "_") + ".glsl"), src);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				};
			}
			ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
			for (String p : programs) {
				pool.submit(() -> {
					try {
						var program = compiler.program(p);
						boolean gbuffers = p.startsWith("gbuffers") || p.startsWith("shadow") && !p.startsWith("shadowcomp");
						PackSamplers samplers = new PackSamplers(program.consts(), gbuffers);
						if (program.compute() != null) {
							var m = compiler.compileCompute(p, samplers);
							write(dir.resolve(p + ".comp.metal"), m.source(), msl);
							ok.incrementAndGet();
							return;
						}
						List<ProgramAssembler.Adapter> adapters = new ArrayList<>();
						boolean terrain = p.startsWith("gbuffers_terrain") || p.equals("gbuffers_water") || p.equals("gbuffers_block") || p.equals("gbuffers_damagedblock");
						if (p.startsWith("shadow")) {
							adapters.add(Adapters.sodium(true, "", new int[0]));
							adapters.add(Adapters.vanilla(entityLayout(), false, true, false, "> 0.1", new int[0]));
						} else if (terrain) {
							adapters.add(Adapters.sodium(false, "> 0.1", new int[] {0, 1, 2, 3, 4, 5, 6, 7}));
						} else if (gbuffers) {
							adapters.add(Adapters.vanilla(entityLayout(), false, false, false, "> 0.1", new int[] {0, 1, 2, 3, 4, 5, 6, 7}));
						} else {
							adapters.add(Adapters.composite());
						}
						for (ProgramAssembler.Adapter adapter : adapters) {
							var c = compiler.compile(p, adapter, samplers);
							String tag = adapter.key().replaceAll("[^A-Za-z0-9]", "_");
							write(dir.resolve(p + "." + tag + ".vert.metal"), c.vertex().source(), msl);
							write(dir.resolve(p + "." + tag + ".frag.metal"), c.fragment().source(), msl);
							slots(p + " vertex", c.vertex(), errors);
							slots(p + " fragment", c.fragment(), errors);
						}
						ok.incrementAndGet();
					} catch (Throwable t) {
						errors.add(p + ": " + t.getMessage());
					}
				});
			}
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.MINUTES);
			System.out.printf("%s [%s]: %d/%d programs translated in %.1f s, %d options%n", source.name(), folder, ok.get(), programs.size(), (System.nanoTime() - start) / 1e9,
				options.options().size());
			for (String e : errors) System.out.println("  ERROR " + e.replace("\n", "\n        "));
			List<String> cmd = new ArrayList<>();
			cmd.add(System.getProperty("mslcheck"));
			synchronized (msl) {
				for (Path p : msl) cmd.add(p.toString());
			}
			if (cmd.size() > 1) {
				Process proc = new ProcessBuilder(cmd).inheritIO().start();
				proc.waitFor();
			}
		}
	}

	private static Adapters.Layout entityLayout() {
		return new Adapters.Layout("new_entity", List.of(new Adapters.Attribute("Position", 0, "RGB32_FLOAT"), new Adapters.Attribute("Color", 1, "RGBA8_UNORM"),
			new Adapters.Attribute("UV0", 2, "RG32_FLOAT"), new Adapters.Attribute("UV1", 3, "RG16_SINT"), new Adapters.Attribute("UV2", 4, "RG16_SINT"),
			new Adapters.Attribute("Normal", 5, "RGB8_SNORM")), true);
	}

	private static void slots(String what, ShaderCompiler.Msl stage, java.util.Collection<String> errors) {
		for (ShaderCompiler.Texture t : stage.reflection().textures()) {
			var m = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(t.name()) + " \\[\\[texture\\((\\d+)\\)\\]\\]").matcher(stage.source());
			if (m.find() && Integer.parseInt(m.group(1)) != t.slot()) errors.add(what + ": " + t.name() + " reflected at " + t.slot() + " but bound at " + m.group(1));
		}
	}

	private static void write(Path p, String s, List<Path> list) throws Exception {
		Files.writeString(p, s);
		synchronized (list) {
			list.add(p);
		}
	}
}
