package mcopt.metal.shaderpack.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mcopt.metal.MetalBridge;
import mcopt.metal.MetalHooks;
import mcopt.metal.PassDelegate;
import mcopt.metal.PipelineInfo;
import mcopt.metal.shaderpack.Adapters;
import mcopt.metal.shaderpack.PackBuffers;
import mcopt.metal.shaderpack.PackCompiler;
import mcopt.metal.shaderpack.PackMacros;
import mcopt.metal.shaderpack.PackOptions;
import mcopt.metal.shaderpack.PackProperties;
import mcopt.metal.shaderpack.PackSamplers;
import mcopt.metal.shaderpack.PackSource;
import mcopt.metal.shaderpack.PackUniforms;
import mcopt.metal.shaderpack.ProgramAssembler;
import mcopt.metal.shaderpack.ShaderCompiler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class PackRuntime {
	private static final ExecutorService POOL = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 2), r -> {
		Thread t = new Thread(r, "mcopt shader pack");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static final java.util.regex.Pattern META_BLUR = java.util.regex.Pattern.compile("\"blur\"\\s*:\\s*true"),
		META_CLAMP = java.util.regex.Pattern.compile("\"clamp\"\\s*:\\s*true");
	private static final int D32 = 252, TRIANGLE_STRIP = 4, TRIANGLES = 3, RING = 4 << 20;
	private static volatile @Nullable Session session;
	private static volatile String status = "";
	private static volatile int generation;
	private static @Nullable String loadedDimension;
	private static volatile boolean flatShading;

	private PackRuntime() {
	}

	public static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
	}

	public static String status() {
		return status;
	}

	public static boolean flatShading() {
		return flatShading;
	}

	private static void flatShading(boolean on) {
		if (flatShading == on) return;
		flatShading = on;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.level != null) mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
		});
	}

	public static boolean active() {
		Session s = session;
		return s != null && s.ready;
	}

	public static void reload() {
		int ticket = ++generation;
		Session old = session;
		session = null;
		MetalHooks.setRedirector(null);
		if (old != null) old.closeLater();
		String name = ShaderPacks.selected();
		if (name.isEmpty() || !MetalBridge.metalActive()) {
			status = "";
			flatShading(false);
			return;
		}
		Object encoder = MetalBridge.currentEncoder();
		if (encoder == null) return;
		long ctx = MetalBridge.ctx(encoder);
		String dimension = dimension();
		loadedDimension = dimension;
		status = "Loading " + name + "...";
		CompletableFuture.supplyAsync(() -> Session.load(name, dimension, ctx), POOL).whenComplete((s, failure) -> {
			if (ticket != generation) {
				if (s != null) s.closeLater();
				return;
			}
			if (failure != null) {
				Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
				status = "Couldn't load " + name + ": " + cause.getMessage();
				System.out.println("[mcopt] shaderpack: " + status);
				return;
			}
			session = s;
			MetalHooks.setRedirector(PackRuntime::redirect);
			flatShading(!"true".equals(s.compiler.properties().get("oldLighting")));
			status = name + ": " + s.summary;
			System.out.println("[mcopt] shaderpack: " + status);
		});
	}

	private static String dimension() {
		var level = Minecraft.getInstance().level;
		return level == null ? "minecraft:overworld" : level.dimension().identifier().toString();
	}

	public static void beginLevel(Matrix4fc projection, Matrix4fc viewRotation, Vec3 camera, RenderTarget target) {
		Session s = session;
		if (s == null) {
			if (!ShaderPacks.selected().isEmpty() && generation == 0) reload();
			return;
		}
		if (!dimension().equals(loadedDimension)) {
			reload();
			return;
		}
		if (!s.ready) return;
		Object encoder = MetalBridge.currentEncoder();
		if (encoder == null) return;
		try {
			s.begin(encoder, projection, viewRotation, camera, target);
		} catch (RuntimeException e) {
			s.fail(e);
		}
	}

	public static boolean forceClassicTransparency() {
		Session s = session;
		return s != null;
	}

	public static void beforeTranslucent() {
		Session s = session;
		if (s == null || !s.inLevel) return;
		try {
			s.beforeTranslucent();
		} catch (RuntimeException e) {
			s.fail(e);
		}
	}

	public static void hand(boolean begin) {
		Session s = session;
		if (s == null || !s.inLevel) return;
		s.hand(begin);
	}

	public static void endLevel() {
		Session s = session;
		if (s == null || !s.inLevel) return;
		try {
			s.end();
		} catch (RuntimeException e) {
			s.fail(e);
		}
	}

	private static MetalHooks.@Nullable Redirect redirect(RenderPassDescriptor descriptor) {
		Session s = session;
		if (s == null || !s.inLevel) return null;
		return s.redirect(descriptor);
	}

	static final class Target {
		final int format;
		long main, alt;
		int width, height;
		boolean flipped;
		final float @Nullable [] clear;

		Target(int format, float @Nullable [] clear) {
			this.format = format;
			this.clear = clear;
		}

		long read() {
			return this.flipped ? this.alt : this.main;
		}

		long write() {
			return this.flipped ? this.main : this.alt;
		}
	}

	record Batch(GpuBuffer vertices, long vertexOffset, GpuBuffer indices, boolean intIndices, byte[] push, long[] offsets, int[] counts, int[] baseVertices, boolean cutout) {
	}

	static final class Session implements PassDelegate {
		final String name;
		final PackCompiler compiler;
		final Map<String, String> consts;
		final PackUniforms uniforms;
		final PackFrame frame = new PackFrame();
		final long ctx;
		final List<String> prepare, deferred, composite;
		final boolean hasFinal;
		final int[] gbufferBuffers;
		final Set<Integer> usedBuffers;
		final String summary;
		final Map<String, String> uniformDefaults = new HashMap<>();
		final Map<String, PackSamplers> samplers = new ConcurrentHashMap<>();
		final Map<String, CompletableFuture<PackPipeline>> pipelines = new ConcurrentHashMap<>();
		final Map<String, Long> customTextures = new HashMap<>();
		final Map<String, int[]> passMipmaps = new HashMap<>();
		final Set<Integer> mipmapped = new TreeSet<>();
		final boolean shadows;
		final int shadowResolution;
		volatile boolean ready;
		boolean inLevel, gbuffersStarted, translucentDone, handActive, failed, shadowPass;
		long atlas;
		Target[] targets = new Target[PackBuffers.MAX];
		long depth0, depth1, depth2, shadowDepth, shadowColor0, shadowColor1, noise, blockIds, white, black, normalDefault;
		long nearestSampler;
		int width, height;
		final long[] rings = new long[3];
		int ring, ringOffset;
		@Nullable RenderTarget worldTarget;
		@Nullable PackPipeline current;
		@Nullable PipelineInfo currentInfo;
		List<Batch> recording = new ArrayList<>(), replay = new ArrayList<>();
		final byte[] push = new byte[32];
		int pushLength;
		final ByteBuffer frameBlock = MemoryUtil.memCalloc(256);
		final List<Long> staging = new ArrayList<>();

		private Session(String name, long ctx, PackCompiler compiler, Map<String, String> consts, PackUniforms uniforms, List<String> prepare, List<String> deferred,
			List<String> composite, boolean hasFinal, int[] gbufferBuffers, Set<Integer> usedBuffers, String summary) {
			this.name = name;
			this.compiler = compiler;
			this.consts = consts;
			this.uniforms = uniforms;
			this.prepare = prepare;
			this.deferred = deferred;
			this.composite = composite;
			this.hasFinal = hasFinal;
			this.gbufferBuffers = gbufferBuffers;
			this.usedBuffers = usedBuffers;
			this.summary = summary;
			this.ctx = ctx;
			this.shadows = compiler.resolve("shadow") != null && !"0".equals(consts.get("shadowMapResolution"));
			this.shadowResolution = Math.max(256, Math.min(8192, (int) PackFrame.constFloat(consts, "shadowMapResolution", 1024)));
		}

		static Session load(String name, String dimension, long ctx) {
			long start = System.nanoTime();
			try {
				Path path = directory().resolve(name);
				PackSource source = PackSource.open(path);
				Map<String, String> values = PackOptions.readValues(directory().resolve(name + ".txt"));
				PackOptions options = PackOptions.discover(source, values);
				var mc = Minecraft.getInstance();
				PackMacros.Environment env = new PackMacros.Environment(mcVersion(), mc.options.mipmapLevels().get(), 1, 1, false, false, 1);
				Map<String, String> macros = PackCompiler.macros(options, env);
				PackProperties properties = PackProperties.parse(source.read("shaders.properties"), macros);
				PackProperties dims = source.exists("dimension.properties") ? PackProperties.parse(source.read("dimension.properties"), macros) : null;
				String folder = PackCompiler.dimensionFolder(source, dimension, dims);
				PackCompiler compiler = new PackCompiler(source, options, properties, PackMacros.standard(env), folder);
				List<String> names = new ArrayList<>();
				for (String p : PackCompiler.GBUFFERS) if (compiler.exists(p)) names.add(p);
				for (String p : PackCompiler.SHADOWS) if (compiler.exists(p)) names.add(p);
				List<String> prepare = compiler.passes("prepare"), deferred = compiler.passes("deferred"), composite = compiler.passes("composite");
				names.addAll(prepare);
				names.addAll(deferred);
				names.addAll(composite);
				boolean hasFinal = compiler.exists("final");
				if (hasFinal) names.add("final");
				List<CompletableFuture<PackCompiler.Program>> parsed = new ArrayList<>();
				for (String n : names) parsed.add(CompletableFuture.supplyAsync(() -> {
					try {
						return compiler.program(n);
					} catch (Exception e) {
						System.out.println("[mcopt] shaderpack: " + n + ": " + e.getMessage());
						return null;
					}
				}, POOL));
				Map<String, String> consts = new HashMap<>();
				Set<Integer> gbuffer = new TreeSet<>(), used = new TreeSet<>();
				Map<String, String> defaults = new HashMap<>();
				Map<String, int[]> passMipmaps = new HashMap<>();
				for (CompletableFuture<PackCompiler.Program> f : parsed) {
					PackCompiler.Program p = f.join();
					if (p == null) continue;
					consts.putAll(p.consts());
					if (!p.name().startsWith("gbuffers_") && !p.name().startsWith("shadow")) {
						int[] mips = java.util.stream.IntStream.range(0, PackBuffers.MAX).filter(b -> PackBuffers.mipmapped(p.consts(), b)).toArray();
						if (mips.length > 0) passMipmaps.put(p.name(), mips);
					}
					if (p.name().startsWith("gbuffers_")) gbuffer.addAll(p.drawBuffers());
					if (!p.name().startsWith("shadow")) used.addAll(p.drawBuffers());
					for (var stage : new PackCompiler.Stage[] {p.vertex(), p.fragment()}) {
						if (stage == null) continue;
						defaults.putAll(stage.result().uniformDefaults);
						for (String sampler : stage.result().samplers.keySet()) {
							int b = PackBuffers.index(sampler);
							if (b >= 0) used.add(b);
						}
					}
				}
				used.addAll(gbuffer);
				int[] gb = gbuffer.stream().limit(8).mapToInt(Integer::intValue).toArray();
				PackUniforms uniforms = new PackUniforms(properties.customUniforms());
				String summary = String.format("%d programs, %d buffers, %.1f s", names.size(), used.size(), (System.nanoTime() - start) / 1e9);
				Session s = new Session(name, ctx, compiler, consts, uniforms, prepare, deferred, composite, hasFinal, gb, used, summary);
				s.uniformDefaults.putAll(defaults);
				s.passMipmaps.putAll(passMipmaps);
				for (int[] mips : passMipmaps.values()) for (int b : mips) s.mipmapped.add(b);
				s.ready = true;
				for (String n : prepare) s.fullScreen(n);
				for (String n : deferred) s.fullScreen(n);
				for (String n : composite) s.fullScreen(n);
				return s;
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
			}
		}

		private static String mcVersion() {
			return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("26.3");
		}

		void fail(RuntimeException e) {
			if (this.failed) return;
			this.failed = true;
			status = this.name + ": " + e.getMessage();
			System.out.println("[mcopt] shaderpack: disabled after an error: " + e);
			e.printStackTrace();
			session = null;
			MetalHooks.setRedirector(null);
			flatShading(false);
		}

		void closeLater() {
			this.ready = false;
		}

		PackSamplers samplers(String program) {
			return this.samplers.computeIfAbsent(program, p -> {
				try {
					boolean gbuffers = p.startsWith("gbuffers") || (p.startsWith("shadow") && !p.startsWith("shadowcomp"));
					return new PackSamplers(this.compiler.program(p).consts(), gbuffers, customSamplers(stage(p)));
				} catch (Exception e) {
					return new PackSamplers(Map.of(), true);
				}
			});
		}

		static String stage(String program) {
			if (program.startsWith("gbuffers_")) return "gbuffers";
			if (program.startsWith("shadowcomp")) return "shadowcomp";
			if (program.startsWith("shadow")) return "shadow";
			if (program.startsWith("prepare")) return "prepare";
			if (program.startsWith("deferred")) return "deferred";
			return "composite";
		}

		private Map<String, ShaderCompiler.Sampler> customSamplers(String stage) {
			Map<String, ShaderCompiler.Sampler> out = new HashMap<>();
			for (var e : this.compiler.properties().textures(stage).entrySet()) {
				boolean blur = false, clamp = false;
				try {
					byte[] meta = this.compiler.source().bytes(e.getValue().trim() + ".mcmeta");
					if (meta != null) {
						String m = new String(meta, java.nio.charset.StandardCharsets.UTF_8);
						blur = META_BLUR.matcher(m).find();
						clamp = META_CLAMP.matcher(m).find();
					}
				} catch (Exception ignored) {
				}
				out.put(PackSamplers.customKey(e.getKey()), new ShaderCompiler.Sampler(blur, false, !clamp, false));
			}
			return out;
		}

		void ensureTargets(Object encoder, int w, int h) {
			if (w == this.width && h == this.height && this.depth0 != 0) return;
			this.width = w;
			this.height = h;
			for (int i = 0; i < PackBuffers.MAX; i++) {
				Target old = this.targets[i];
				if (old != null) {
					MetalBridge.release(old.main);
					MetalBridge.release(old.alt);
					this.targets[i] = null;
				}
				if (!this.usedBuffers.contains(i)) continue;
				int format = PackBuffers.metalFormat(PackBuffers.format(this.consts, i));
				Target t = new Target(format, clearColor(i));
				float[] scale = this.compiler.properties().bufferSize("colortex" + i);
				int tw = w, th = h;
				if (scale != null) {
					tw = scale[0] <= 2 ? Math.max(1, Math.round(w * scale[0])) : (int) scale[0];
					th = scale[1] <= 2 ? Math.max(1, Math.round(h * scale[1])) : (int) scale[1];
				}
				int levels = this.mipmapped.contains(i) ? 32 - Integer.numberOfLeadingZeros(Math.max(tw, th)) : 1;
				t.main = MetalBridge.texture(this.ctx, format, tw, th, levels, 5);
				t.alt = MetalBridge.texture(this.ctx, format, tw, th, levels, 5);
				t.width = tw;
				t.height = th;
				this.targets[i] = t;
				float[] initial = t.clear == null ? new float[] {0, 0, 0, 0} : t.clear;
				long enc = MetalBridge.enc(encoder);
				MetalBridge.renderBegin(enc, new long[] {t.main, t.alt}, new float[][] {initial, initial}, 0, false, 0, tw, th);
				MetalBridge.endRender(enc);
			}
			for (long d : new long[] {this.depth0, this.depth1, this.depth2}) if (d != 0) MetalBridge.release(d);
			this.depth0 = MetalBridge.texture(this.ctx, D32, w, h, 1, 5);
			this.depth1 = MetalBridge.texture(this.ctx, D32, w, h, 1, 5);
			this.depth2 = MetalBridge.texture(this.ctx, D32, w, h, 1, 5);
			if (this.shadowDepth == 0) {
				int r = this.shadowResolution;
				this.shadowDepth = MetalBridge.texture(this.ctx, D32, r, r, 1, 5);
				this.shadowColor0 = MetalBridge.texture(this.ctx, PackBuffers.metalFormat(shadowFormat(0)), r, r, 1, 5);
				this.shadowColor1 = MetalBridge.texture(this.ctx, PackBuffers.metalFormat(shadowFormat(1)), r, r, 1, 5);
				this.nearestSampler = MetalBridge.sampler(this.ctx, false, false, false, 1);
				this.white = solid(encoder, 0xFFFFFFFF);
				this.black = solid(encoder, 0);
				this.normalDefault = solid(encoder, 0xFFFF8080);
				this.noise = noise(encoder);
				this.blockIds = BlockIds.build(this.ctx, encoder, this.compiler, this.staging);
				for (String stage : List.of("gbuffers", "deferred", "composite", "shadow", "prepare")) {
					for (var e : this.compiler.properties().textures(stage).entrySet()) {
							long tex = loadTexture(encoder, e.getValue());
							if (tex != 0) this.customTextures.put(stage + ":" + PackSamplers.customKey(e.getKey()), tex);
					}
				}
				for (int i = 0; i < 3; i++) this.rings[i] = MetalBridge.newBuffer(this.ctx, RING);
			}
		}

		private String shadowFormat(int i) {
			String v = this.consts.get("shadowcolor" + i + "Format");
			return v == null ? "RGBA8" : v.trim();
		}

		private float @Nullable [] clearColor(int i) {
			String flag = PackBuffers.constant(this.consts, i, "Clear");
			if (flag != null && flag.trim().equals("false")) return null;
			String color = PackBuffers.constant(this.consts, i, "ClearColor");
			if (color != null) {
				String[] parts = color.replaceAll("[^0-9.,eE+-]", " ").trim().split("[\\s,]+");
				float[] c = new float[] {0, 0, 0, 0};
				for (int k = 0; k < parts.length && k < 4; k++) {
					try {
						c[k] = Float.parseFloat(parts[k]);
					} catch (NumberFormatException ignored) {
					}
				}
				if (parts.length == 1) c[1] = c[2] = c[3] = c[0];
				return c;
			}
			return i == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
		}

		private long solid(Object encoder, int abgr) {
			long tex = MetalBridge.texture(this.ctx, 70, 1, 1, 1, 1);
			long buf = MetalBridge.newBuffer(this.ctx, 256);
			MemoryUtil.memPutInt(MetalBridge.bufferContents(buf), abgr);
			MetalBridge.upload(MetalBridge.enc(encoder), buf, 0, 4, tex, 1, 1);
			this.staging.add(buf);
			return tex;
		}

		private long noise(Object encoder) {
			String custom = this.compiler.properties().get("texture.noise");
			if (custom != null) {
				long t = loadTexture(encoder, custom);
				if (t != 0) return t;
			}
			int size = Math.max(16, Math.min(4096, (int) PackFrame.constFloat(this.consts, "noiseTextureResolution", 256)));
			long tex = MetalBridge.texture(this.ctx, 70, size, size, 1, 1);
			long buf = MetalBridge.newBuffer(this.ctx, (long) size * size * 4);
			long at = MetalBridge.bufferContents(buf);
			java.util.Random random = new java.util.Random(0);
			for (int i = 0; i < size * size; i++) MemoryUtil.memPutInt(at + i * 4L, random.nextInt());
			MetalBridge.upload(MetalBridge.enc(encoder), buf, 0, size * 4, tex, size, size);
			this.staging.add(buf);
			return tex;
		}

		private long loadTexture(Object encoder, String spec) {
			String path = spec.trim();
			if (path.startsWith("minecraft:") || path.contains(" ")) return 0;
			try {
				byte[] bytes = this.compiler.source().bytes(path);
				if (bytes == null) return 0;
				ByteBuffer data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
				int[] w = new int[1], h = new int[1], c = new int[1];
				ByteBuffer pixels = org.lwjgl.stb.STBImage.stbi_load_from_memory(data, w, h, c, 4);
				MemoryUtil.memFree(data);
				if (pixels == null) return 0;
				long tex = MetalBridge.texture(this.ctx, 70, w[0], h[0], 1, 1);
				long buf = MetalBridge.newBuffer(this.ctx, (long) w[0] * h[0] * 4);
				MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), MetalBridge.bufferContents(buf), (long) w[0] * h[0] * 4);
				org.lwjgl.stb.STBImage.stbi_image_free(pixels);
				MetalBridge.upload(MetalBridge.enc(encoder), buf, 0, w[0] * 4, tex, w[0], h[0]);
				this.staging.add(buf);
				return tex;
			} catch (Exception e) {
				return 0;
			}
		}

		long ringWrite(PackUniforms.Block block) {
			int size = (block.size() + 255) & ~255;
			if (this.ringOffset + size > RING) this.ringOffset = 0;
			long buffer = this.rings[this.ring];
			block.write(MetalBridge.bufferContents(buffer) + this.ringOffset);
			long offset = this.ringOffset;
			this.ringOffset += size;
			return offset;
		}

		void bindUniforms(long enc, PackPipeline p) {
			long buffer = this.rings[this.ring];
			if (p.frame != this.frame.frameCounter) {
				p.frame = this.frame.frameCounter;
				p.vertexOffset = ringWrite(p.vertexBlock);
				p.fragmentOffset = ringWrite(p.fragmentBlock);
			}
			MetalBridge.bindBuffer(enc, ShaderCompiler.PACK_UNIFORMS, buffer, p.vertexOffset);
			MetalBridge.bindBuffer(enc, ShaderCompiler.PACK_UNIFORMS + 1, buffer, p.fragmentOffset);
			MetalBridge.bindBytes(enc, Adapters.FRAME_SLOT, MemoryUtil.memAddress(this.frameBlock), 256);
		}

		void bindTextures(long enc, PackPipeline p, boolean gbuffers) {
			for (ShaderCompiler.Texture t : p.textures) {
				if (t.sampler() >= 0 && t.sampler() <= PackSamplers.LIGHTMAP) {
					if (gbuffers && !this.shadowPass) continue;
					long tex = t.sampler() == PackSamplers.ALBEDO ? (this.shadowPass && this.atlas != 0 ? this.atlas : resolve("colortex0", false)) : this.white;
					MetalBridge.bindTexture(enc, t.slot(), tex == 0 ? this.black : tex, this.nearestSampler);
					continue;
				}
				long tex = resolve(t.name(), gbuffers, stage(p.program));
				MetalBridge.bindTexture(enc, t.slot(), tex == 0 ? this.black : tex, t.sampler() >= 0 ? this.nearestSampler : 0);
			}
			if (this.blockIds != 0) MetalBridge.bindTexture(enc, PackSamplers.BLOCK_IDS, this.blockIds, 0);
		}

		long resolve(String name, boolean gbuffers, String stage) {
			Long custom = this.customTextures.get(stage + ":" + PackSamplers.customKey(name));
			return custom != null ? custom : resolve(name, gbuffers);
		}

		long resolve(String name, boolean gbuffers) {
			int buffer = PackBuffers.index(name);
			if (buffer < 0 && !gbuffers && (name.equals("texture") || name.equals("tex") || name.equals("gtexture"))) buffer = 0;
			if (buffer >= 0) {
				Target t = this.targets[buffer];
				if (t == null) return this.black;
				if (gbuffers) {
					for (int b : this.gbufferBuffers) if (b == buffer) return t.write();
				}
				return t.read();
			}
			return switch (name) {
				case "depthtex0", "gdepthtex" -> gbuffers ? this.depth1 : this.depth0;
				case "depthtex1" -> this.depth1;
				case "depthtex2" -> this.depth2;
				case "shadowtex0", "shadowtex1", "shadow", "watershadow", "shadowtex0HW", "shadowtex1HW" -> this.shadowDepth;
				case "shadowcolor0", "shadowcolor" -> this.shadowColor0;
				case "shadowcolor1" -> this.shadowColor1;
				case "noisetex" -> this.noise;
				case "normals" -> this.normalDefault;
				case "specular" -> this.black;
				default -> this.black;
			};
		}

		CompletableFuture<PackPipeline> fullScreen(String program) {
			return this.pipelines.computeIfAbsent("pass:" + program, k -> CompletableFuture.supplyAsync(() -> {
				try {
					PackCompiler.Compiled c = this.compiler.compile(program, Adapters.composite(), samplers(program));
					List<PackPipeline.Attachment> attachments = new ArrayList<>();
					if (program.equals("final")) {
						attachments.add(new PackPipeline.Attachment(70, 15, null));
					} else {
						for (int b : c.drawBuffers()) {
							Target t = targetFor(b);
							attachments.add(new PackPipeline.Attachment(t == null ? 70 : t.format, 15, blend(program, b, null)));
						}
					}
					return PackPipeline.build(this.ctx, c, this.uniforms, this.uniformDefaults, attachments, 0, null, false, 7, false, false, TRIANGLE_STRIP, 3);
				} catch (Exception e) {
					System.out.println("[mcopt] shaderpack: " + program + ": " + e.getMessage());
					return null;
				}
			}, POOL));
		}

		private @Nullable Target targetFor(int buffer) {
			if (buffer < 0 || buffer >= PackBuffers.MAX) return null;
			if (this.targets[buffer] == null) {
				int format = PackBuffers.metalFormat(PackBuffers.format(this.consts, buffer));
				return new Target(format, null);
			}
			return this.targets[buffer];
		}

		private int @Nullable [] blend(String program, int buffer, int @Nullable [] fallback) {
			PackProperties props = this.compiler.properties();
			PackProperties.Blend b = props.blend(program, "colortex" + buffer);
			if (b == null) b = props.blend(program, null);
			if (b == null) return fallback;
			if (b.off()) return null;
			return new int[] {mtlFactor(b.srcColor()), mtlFactor(b.dstColor()), 0, mtlFactor(b.srcAlpha()), mtlFactor(b.dstAlpha()), 0};
		}

		private static int mtlFactor(int f) {
			return switch (f) {
				case 0 -> 0;
				case 1 -> 1;
				case 2 -> 2;
				case 3 -> 3;
				case 4 -> 4;
				case 5 -> 5;
				case 6 -> 6;
				case 7 -> 7;
				case 8 -> 8;
				case 9 -> 9;
				case 10 -> 10;
				default -> 1;
			};
		}

		void begin(Object encoder, Matrix4fc projection, Matrix4fc viewRotation, Vec3 camera, RenderTarget target) {
			long enc = MetalBridge.enc(encoder);
			this.worldTarget = target;
			ensureTargets(encoder, target.width, target.height);
			this.ring = (this.ring + 1) % this.rings.length;
			this.ringOffset = 0;
			this.frame.camera(projection, viewRotation, camera);
			this.frame.update(this.uniforms, this.width, this.height, this.consts);
			this.uniforms.evaluateCustom();
			writeFrameBlock();
			this.inLevel = true;
			this.gbuffersStarted = false;
			this.translucentDone = false;
			this.handActive = false;
			for (Target t : this.targets) if (t != null) t.flipped = false;
			for (Target t : this.targets) if (t != null && t.clear != null) clearTarget(t);
			if (this.shadows) renderShadows(enc);
			runPasses(enc, this.prepare, false);
		}

		private void writeFrameBlock() {
			long a = MemoryUtil.memAddress(this.frameBlock);
			float[] m = new float[16];
			this.frame.shadowModelView.get(m);
			for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(a + i * 4L, m[i]);
			this.frame.shadowProjection.get(m);
			for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(a + 64 + i * 4L, m[i]);
			PackUniforms.Value fog = this.uniforms.get("fogColor");
			for (int i = 0; i < 3; i++) MemoryUtil.memPutFloat(a + 128 + i * 4L, (float) fog.v[i]);
			MemoryUtil.memPutFloat(a + 140, 1);
			MemoryUtil.memPutFloat(a + 144, (float) this.uniforms.get("fogDensity").v[0]);
			MemoryUtil.memPutFloat(a + 148, (float) this.uniforms.get("fogStart").v[0]);
			MemoryUtil.memPutFloat(a + 152, (float) this.uniforms.get("fogEnd").v[0]);
			double range = this.uniforms.get("fogEnd").v[0] - this.uniforms.get("fogStart").v[0];
			MemoryUtil.memPutFloat(a + 156, (float) (range == 0 ? 1 : 1 / range));
		}

		void runPasses(long enc, List<String> passes, boolean last) {
			for (String name : passes) runPass(enc, name, null);
			if (last && this.hasFinal && this.worldTarget != null) {
				Object encoder = MetalBridge.currentEncoder();
				if (encoder != null) MetalBridge.dropPendingClear(encoder, this.worldTarget.getColorTexture());
				runPass(enc, "final", MetalBridge.textureHandle(this.worldTarget.getColorTexture()));
			}
		}

		private void runPass(long enc, String name, @Nullable Long output) {
			CompletableFuture<PackPipeline> f = fullScreen(name);
			PackPipeline p = f.isDone() ? f.join() : null;
			if (p == null) return;
			long[] colors;
			List<Integer> written = new ArrayList<>();
			if (output != null) {
				colors = new long[] {output};
			} else {
				colors = new long[p.drawBuffers.size()];
				for (int i = 0; i < colors.length; i++) {
					Target t = this.targets[p.drawBuffers.get(i)];
					if (t == null) return;
					colors[i] = t.write();
					written.add(p.drawBuffers.get(i));
				}
			}
			int[] mips = this.passMipmaps.get(name);
			if (mips != null) {
				for (int b : mips) {
					Target t = this.targets[b];
					if (t != null) MetalBridge.generateMipmaps(enc, t.read());
				}
			}
			int w = this.width, h = this.height;
			MetalBridge.renderBegin(enc, colors, null, 0, false, 0, w, h);
			MetalBridge.bindPipeline(enc, p.pso, 0, false, 0, 0, TRIANGLE_STRIP);
			bindTextures(enc, p, false);
			bindUniforms(enc, p);
			MetalBridge.draw(enc, 4, 1, 0);
			MetalBridge.endRender(enc);
			for (int b : written) {
				Target t = this.targets[b];
				Boolean flip = this.compiler.properties().flip(name, "colortex" + b);
				if (flip == null || flip) t.flipped = !t.flipped;
			}
		}

		MetalHooks.@Nullable Redirect redirect(RenderPassDescriptor descriptor) {
			RenderTarget target = this.worldTarget;
			if (target == null || this.gbufferBuffers.length == 0) return null;
			var colors = descriptor.colorAttachments();
			if (colors.isEmpty() || colors.getFirst() == null) return null;
			if (colors.getFirst().textureView().texture() != target.getColorTexture()) return null;
			boolean first = !this.gbuffersStarted;
			this.gbuffersStarted = true;
			long[] handles = new long[this.gbufferBuffers.length];
			float[][] clears = new float[handles.length][];
			for (int i = 0; i < handles.length; i++) {
				Target t = this.targets[this.gbufferBuffers[i]];
				handles[i] = t.read();
			}
			return new MetalHooks.Redirect(handles, clears, this.width, this.height, this.depth0, first ? 1F : MetalHooks.Redirect.NO_CLEAR, this);
		}

		private void clearTarget(Target t) {
			Object encoder = MetalBridge.currentEncoder();
			if (encoder == null) return;
			long enc = MetalBridge.enc(encoder);
			MetalBridge.renderBegin(enc, new long[] {t.main}, new float[][] {t.clear}, 0, false, 0, t.width, t.height);
			MetalBridge.endRender(enc);
		}

		void beforeTranslucent() {
			if (this.translucentDone) return;
			Object encoder = MetalBridge.currentEncoder();
			if (encoder == null) return;
			long enc = MetalBridge.enc(encoder);
			java.util.function.Supplier<long @Nullable []> work = () -> {
				MetalBridge.blitTextureToTexture(enc, this.depth0, this.depth1, 0, this.width, this.height);
				runPasses(enc, this.deferred, false);
				this.translucentDone = true;
				long[] handles = new long[this.gbufferBuffers.length];
				for (int i = 0; i < handles.length; i++) handles[i] = this.targets[this.gbufferBuffers[i]].read();
				return handles;
			};
			if (!MetalBridge.splitPass(encoder, work)) work.get();
		}

		void hand(boolean begin) {
			if (begin && !this.handActive) {
				Object encoder = MetalBridge.currentEncoder();
				if (encoder != null && !MetalBridge.inRenderPass(encoder)) {
					MetalBridge.blitTextureToTexture(MetalBridge.enc(encoder), this.depth0, this.depth2, 0, this.width, this.height);
				}
			}
			this.handActive = begin;
		}

		void end() {
			this.inLevel = false;
			Object encoder = MetalBridge.currentEncoder();
			if (encoder == null || !this.gbuffersStarted) return;
			long enc = MetalBridge.enc(encoder);
			if (!this.translucentDone) {
				MetalBridge.blitTextureToTexture(enc, this.depth0, this.depth1, 0, this.width, this.height);
				runPasses(enc, this.deferred, false);
				this.translucentDone = true;
			}
			if (!this.handActive) MetalBridge.blitTextureToTexture(enc, this.depth0, this.depth2, 0, this.width, this.height);
			runPasses(enc, this.composite, true);
			for (Target t : this.targets) {
				if (t == null || !t.flipped) continue;
				long m = t.main;
				t.main = t.alt;
				t.alt = m;
				t.flipped = false;
			}
			List<Batch> done = this.replay;
			this.replay = this.recording;
			this.recording = done;
			this.recording.clear();
		}

		private void renderShadows(long enc) {
			if (this.replay.isEmpty()) return;
			int r = this.shadowResolution;
			MetalBridge.renderBegin(enc, new long[] {this.shadowColor0, this.shadowColor1}, new float[][] {{1, 1, 1, 1}, {1, 1, 1, 1}}, this.shadowDepth, true, 1, r, r);
			this.shadowPass = true;
			for (Batch b : this.replay) {
				if (MetalBridge.closed(b.vertices()) || MetalBridge.closed(b.indices())) continue;
				PackPipeline p = shadowPipeline(b.cutout());
				if (p == null) continue;
				MetalBridge.bindPipeline(enc, p.pso, p.depthState, false, 0, 0, TRIANGLES);
				bindTextures(enc, p, true);
				bindUniforms(enc, p);
				try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
					ByteBuffer pc = stack.malloc(b.push().length).put(b.push()).flip();
					MetalBridge.bindBytes(enc, Adapters.PUSH_CONSTANTS_SLOT, MemoryUtil.memAddress(pc), b.push().length);
				}
				MetalBridge.vertexBuffer(enc, Adapters.SODIUM_VERTICES_SLOT, MetalBridge.bufferHandle(b.vertices()), b.vertexOffset());
				MetalBridge.index(enc, MetalBridge.bufferHandle(b.indices()), b.intIndices());
				int size = b.intIndices() ? 4 : 2;
				for (int i = 0; i < b.counts().length; i++) MetalBridge.drawIndexed(enc, b.counts()[i], 1, (int) (b.offsets()[i] / size), b.baseVertices()[i], 0);
			}
			this.shadowPass = false;
			MetalBridge.endRender(enc);
		}

		private @Nullable PackPipeline shadowPipeline(boolean cutout) {
			String program = this.compiler.resolve(cutout ? "shadow_cutout" : "shadow_solid");
			if (program == null) return null;
			String key = "shadow:" + program + ":" + cutout;
			CompletableFuture<PackPipeline> f = this.pipelines.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
				try {
					String alpha = cutout ? Adapters.alpha(this.compiler.properties().alphaTest(program), 0.1F) : Adapters.alpha(this.compiler.properties().alphaTest(program), 0);
					PackCompiler.Compiled c = this.compiler.compile(program, Adapters.sodium(true, alpha, new int[] {0, 1}), samplers(program));
					List<PackPipeline.Attachment> attachments = List.of(new PackPipeline.Attachment(PackBuffers.metalFormat(shadowFormat(0)), 15, null),
						new PackPipeline.Attachment(PackBuffers.metalFormat(shadowFormat(1)), 15, null));
					return PackPipeline.build(this.ctx, c, this.uniforms, this.uniformDefaults, attachments, D32, null, true, 3, true, false, TRIANGLES, 3);
				} catch (Exception e) {
					System.out.println("[mcopt] shaderpack: " + program + " (shadow): " + e.getMessage());
					return null;
				}
			}, POOL));
			return f.isDone() ? f.join() : null;
		}

		private static int forwardCompare(int compare) {
			return switch (compare) {
				case 1 -> 4;
				case 3 -> 6;
				case 4 -> 1;
				case 6 -> 3;
				default -> compare;
			};
		}

		private @Nullable PackPipeline gbufferPipeline(PipelineInfo info) {
			boolean hand = this.handActive;
			String key = "g:" + info.name() + ":" + System.identityHashCode(info) + ":" + hand;
			CompletableFuture<PackPipeline> f = this.pipelines.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> buildGbuffer(info, hand), POOL));
			return f.isDone() ? f.join() : null;
		}

		private @Nullable PackPipeline buildGbuffer(PipelineInfo info, boolean hand) {
			String wanted = Programs.program(info, hand);
			if (wanted == null) return null;
			String program = this.compiler.resolve(wanted);
			if (program == null) return null;
			try {
				PackCompiler.Program parsed = this.compiler.program(program);
				int[] outputs = new int[parsed.drawBuffers().size()];
				for (int j = 0; j < outputs.length; j++) {
					outputs[j] = -1;
					for (int k = 0; k < this.gbufferBuffers.length; k++) if (this.gbufferBuffers[k] == parsed.drawBuffers().get(j)) outputs[j] = k;
				}
				PackProperties.AlphaTest test = this.compiler.properties().alphaTest(program);
				String alpha = Adapters.alpha(test, info.name().contains("cutout") ? 0.1F : 0);
				ProgramAssembler.Adapter adapter;
				if (info.name().startsWith("sodium:")) {
					adapter = Adapters.sodium(false, alpha, outputs);
				} else {
					List<Adapters.Attribute> attributes = new ArrayList<>();
					for (PipelineInfo.Attrib a : info.attribs()) if (!a.name().isEmpty()) attributes.add(new Adapters.Attribute(a.name(), a.location(), a.format()));
					adapter = Adapters.vanilla(new Adapters.Layout(info.name(), attributes, info.topologyClass() == 3), mcVersion().startsWith("26.2"), false, hand, alpha, outputs);
				}
				PackCompiler.Compiled c = this.compiler.compile(program, adapter, samplers(program));
				List<PackPipeline.Attachment> attachments = new ArrayList<>();
				for (int k = 0; k < this.gbufferBuffers.length; k++) {
					boolean writes = false;
					for (int o : outputs) writes |= o == k;
					Target t = targetFor(this.gbufferBuffers[k]);
					attachments.add(new PackPipeline.Attachment(t == null ? 70 : t.format, writes ? info.writeMask() : 0, blend(program, this.gbufferBuffers[k], info.blend())));
				}
				return PackPipeline.build(this.ctx, c, this.uniforms, this.uniformDefaults, attachments, D32, info, info.depthTest(), forwardCompare(info.depthCompare()), info.depthWrite(),
					info.cull(), info.primitive(), info.topologyClass());
			} catch (Exception e) {
				System.out.println("[mcopt] shaderpack: " + program + " for " + info.name() + ": " + e.getMessage());
				return null;
			}
		}

		@Override
		public int @Nullable [] setPipeline(long enc, Object pipeline, List<BindGroupLayout.UniformDescription> uniforms) {
			PipelineInfo info = MetalBridge.pipelineInfo(pipeline);
			if (info == null) return null;
			PackPipeline p = gbufferPipeline(info);
			this.current = p;
			this.currentInfo = info;
			if (p == null) return null;
			MetalBridge.bindPipeline(enc, p.pso, p.depthState, p.cull, p.biasConstant, p.biasSlope, p.primitive);
			bindTextures(enc, p, true);
			bindUniforms(enc, p);
			int[] slots = new int[uniforms.size()];
			for (int i = 0; i < slots.length; i++) {
				slots[i] = switch (uniforms.get(i).name()) {
					case "DynamicTransforms" -> Adapters.DYNAMIC_TRANSFORMS_SLOT;
					case "Projection" -> Adapters.PROJECTION_SLOT;
					case "u_Globals" -> Adapters.SODIUM_GLOBALS_SLOT;
					case "Sampler0", "u_BlockTex" -> PackSamplers.ALBEDO;
					case "Sampler1" -> PackSamplers.OVERLAY;
					case "Sampler2", "u_LightTex" -> PackSamplers.LIGHTMAP;
					default -> -1;
				};
			}
			return slots;
		}

		@Override
		public void texture(long enc, int slot, Object view) {
			PipelineInfo info = this.currentInfo;
			if (slot == PackSamplers.ALBEDO && info != null && info.name().startsWith("sodium:")) {
				this.atlas = MetalBridge.viewHandle((com.mojang.renderpearl.api.textures.GpuTextureView) view);
			}
		}

		@Override
		public boolean beforeDraw(long enc) {
			return this.current != null;
		}

		@Override
		public void end(long enc) {
			this.current = null;
		}

		@Override
		public void pushConstants(long address, int length) {
			int n = Math.min(length, this.push.length);
			MemoryUtil.memByteBuffer(address, n).get(0, this.push, 0, n);
			this.pushLength = n;
		}

		@Override
		public void multiDraw(Object vertexBuffer, long vertexOffset, Object indexBuffer, boolean intIndices, long offsets, long counts, long baseVertices, int drawCount) {
			PipelineInfo info = this.currentInfo;
			if (!this.shadows || info == null || !info.name().startsWith("sodium:") || info.name().contains("translucent") || this.handActive) return;
			long[] o = new long[drawCount];
			int[] c = new int[drawCount], b = new int[drawCount];
			for (int i = 0; i < drawCount; i++) {
				o[i] = MemoryUtil.memGetLong(offsets + i * 8L);
				c[i] = MemoryUtil.memGetInt(counts + i * 4L);
				b[i] = MemoryUtil.memGetInt(baseVertices + i * 4L);
			}
			byte[] pc = java.util.Arrays.copyOf(this.push, Math.max(this.pushLength, 20));
			this.recording.add(new Batch((GpuBuffer) vertexBuffer, vertexOffset, (GpuBuffer) indexBuffer, intIndices, pc, o, c, b, info.name().contains("cutout")));
		}
	}
}
