package mcopt.metal.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.backend.IrisGpuFormats;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.samplers.ProgramSamplers;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class IrisMetal {
	private static final AtomicInteger THREADS = new AtomicInteger();
	static final ExecutorService COMPILER = Executors.newFixedThreadPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), r -> {
		Thread t = new Thread(r, "mcopt Iris compiler " + THREADS.incrementAndGet());
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private static boolean prepared;
	public static volatile boolean active;

	private IrisMetal() {
	}

	public static boolean metalDevice() {
		return MetalApi.active() && "Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
	}

	public static synchronized void prepare() {
		if (prepared) return;
		prepared = true;
		MetalApi.addStorageUsage(RenderTarget.USAGE_STORAGE);
		MetalApi.registerFormat(IrisGpuFormats.RGB565_UNORM, 40);
		MetalApi.registerFormat(IrisGpuFormats.RGB5A1_UNORM, 41);
		MetalApi.registerFormat(IrisGpuFormats.RGBA4_UNORM, 42);
		MetalApi.registerFormat(IrisGpuFormats.RGB9E5_FLOAT, 93);
	}

	public static PatchedShader patch(Map<PatchShaderType, String> sources, ProgramSamplers resources, DrawUniforms uniforms,
		ColorAttachmentPlan.Program outputs) {
		return MetalPatching.patch(sources, resources, uniforms, outputs);
	}

	static <T> T await(CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException r) throw r;
			if (e.getCause() instanceof Error err) throw err;
			throw e;
		}
	}
}
