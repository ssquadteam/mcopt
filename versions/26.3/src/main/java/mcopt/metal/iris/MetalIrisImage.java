package mcopt.metal.iris;

import com.mojang.renderpearl.api.GpuFormat;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.backend.IrisImage;
import net.irisshaders.iris.gl.texture.TextureType;

import java.nio.ByteBuffer;

final class MetalIrisImage implements IrisImage {
	private final TextureType type;
	private final int width, height, depth, blockSize;
	private long texture;

	MetalIrisImage(String name, TextureType type, GpuFormat format, int width, int height, int depth, boolean storage) {
		if (width < 1 || height < 0 || depth < 0) throw new IllegalArgumentException("invalid image extent for " + name);
		this.type = type;
		this.width = width;
		this.height = type == TextureType.TEXTURE_1D ? 1 : Math.max(1, height);
		this.depth = type == TextureType.TEXTURE_3D ? Math.max(1, depth) : 1;
		this.blockSize = format.blockSize();
		int kind = switch (type) {
			case TEXTURE_1D -> MetalApi.TEXTURE_1D;
			case TEXTURE_3D -> MetalApi.TEXTURE_3D;
			default -> MetalApi.TEXTURE_2D;
		};
		int usage = MetalApi.USAGE_SHADER_READ | (storage ? MetalApi.USAGE_SHADER_WRITE : 0);
		this.texture = MetalApi.newTexture(kind, MetalApi.pixelFormat(format), this.width, this.height, this.depth, 1, usage);
		this.clear();
	}

	@Override
	public TextureType getType() {
		return this.type;
	}

	@Override
	public int getWidth() {
		return this.width;
	}

	@Override
	public int getHeight() {
		return this.height;
	}

	@Override
	public int getDepth() {
		return this.depth;
	}

	@Override
	public long getView() {
		if (this.texture == 0) throw new IllegalStateException("image closed");
		return this.texture;
	}

	@Override
	public void upload(ByteBuffer pixels) {
		MetalApi.writeTexture(this.getView(), pixels, this.width * this.blockSize, this.width * this.height * this.blockSize);
	}

	@Override
	public void clear() {
		MetalApi.writeTexture(this.getView(), null, this.width * this.blockSize, this.width * this.height * this.blockSize);
	}

	@Override
	public void close() {
		if (this.texture != 0) {
			MetalApi.releaseLater(this.texture);
			this.texture = 0;
		}
	}
}
