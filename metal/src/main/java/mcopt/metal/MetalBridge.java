package mcopt.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.foreign.SymbolLookup;

/**
 * The backend's handles for code outside this package (the recorder, the Lunar mixins), so the backend's own classes stay
 * package-private.
 */
public final class MetalBridge {
	private MetalBridge() {
	}

	/** libmcmetal as loaded by the backend. */
	public static SymbolLookup library() {
		return Native.lookup();
	}

	public static long viewHandle(GpuTextureView view) {
		return ((MetalTexture.View) view).handle;
	}

	public static boolean metalActive() {
		return Versioned.metalActive();
	}

	/** Native Enc* of a backend encoder. */
	public static long enc(Object encoder) {
		return ((MetalEncoder) encoder).enc;
	}

	public static long ctx(Object encoder) {
		return ((MetalEncoder) encoder).ctx;
	}

	/** A pending (deferred) clear of texture written to memory now, for reading it outside a render pass. */
	public static void flushClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).flushClear(texture);
	}
}
