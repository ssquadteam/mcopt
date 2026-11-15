
#import "mcmetal.h"

static void copyApiError(NSError *e, char *err, int cap) {
	if (!err || cap <= 0) return;
	const char *s = e ? e.localizedDescription.UTF8String : "unknown error";
	strncpy(err, s ? s : "unknown error", cap - 1);
	err[cap - 1] = 0;
}

void *mc_api_device(Ctx *ctx) { return ctx->device; }
void *mc_api_queue(Ctx *ctx) { return ctx->queue; }
void *mc_api_render_encoder(Enc *enc) { return enc->render; }

void mc_api_viewport(Enc *enc, float x, float y, float w, float h) {
	if (enc->render) [enc->render setViewport:(MTLViewport) {x, y, w, h, 0, 1}];
}

id<MTLTexture> mc_api_texture_new(Ctx *ctx, int type, int format, int width, int height, int depth, int mips, int usage) {
	@autoreleasepool {
		MTLTextureDescriptor *d = [[MTLTextureDescriptor new] autorelease];
		d.textureType = type == 0 ? MTLTextureType1D : type == 2 ? MTLTextureType3D : type == 3 ? MTLTextureType2DArray : MTLTextureType2D;
		d.pixelFormat = (MTLPixelFormat) format;
		d.width = width;
		d.height = type == 0 ? 1 : height;
		if (type == 2) d.depth = depth;
		if (type == 3) d.arrayLength = depth;
		d.mipmapLevelCount = mips;
		d.usage = (MTLTextureUsage) usage;
		d.storageMode = MTLStorageModePrivate;
		return [ctx->device newTextureWithDescriptor:d];
	}
}

id<MTLSamplerState> mc_api_sampler_new(Ctx *ctx, int addressU, int addressV, int addressW, int minFilter, int magFilter, int mipFilter,
	int anisotropy, float maxLod, int compare) {
	@autoreleasepool {
		MTLSamplerDescriptor *d = [[MTLSamplerDescriptor new] autorelease];
		d.sAddressMode = (MTLSamplerAddressMode) addressU;
		d.tAddressMode = (MTLSamplerAddressMode) addressV;
		d.rAddressMode = (MTLSamplerAddressMode) addressW;
		d.minFilter = (MTLSamplerMinMagFilter) minFilter;
		d.magFilter = (MTLSamplerMinMagFilter) magFilter;
		d.mipFilter = (MTLSamplerMipFilter) mipFilter;
		d.maxAnisotropy = anisotropy;
		d.lodMaxClamp = maxLod;
		if (compare >= 0) d.compareFunction = (MTLCompareFunction) compare;
		return [ctx->device newSamplerStateWithDescriptor:d];
	}
}

void mc_api_mipmaps(Enc *enc, id<MTLTexture> texture) {
	if (texture.mipmapLevelCount > 1) [mc_blit(enc) generateMipmapsForTexture:texture];
}

void mc_api_texture_write(Enc *enc, id<MTLTexture> texture, const void *data, int bytesPerRow, int bytesPerImage) {
	@autoreleasepool {
		NSUInteger depth = texture.textureType == MTLTextureType3D ? texture.depth : 1;
		NSUInteger slices = texture.textureType == MTLTextureType2DArray ? texture.arrayLength : 1;
		NSUInteger length = (NSUInteger) bytesPerImage * depth * slices;
		if (length == 0) return;
		id<MTLBuffer> staging;
		if (data) {
			staging = [enc->ctx->device newBufferWithBytes:data length:length options:MTLResourceStorageModeShared];
		} else {
			staging = [enc->ctx->device newBufferWithLength:length options:MTLResourceStorageModeShared];
			memset(staging.contents, 0, length);
		}
		id<MTLBlitCommandEncoder> b = mc_blit(enc);
		for (NSUInteger s = 0; s < slices; s++) {
			[b copyFromBuffer:staging sourceOffset:s * bytesPerImage sourceBytesPerRow:bytesPerRow sourceBytesPerImage:bytesPerImage
				sourceSize:MTLSizeMake(texture.width, texture.height, depth) toTexture:texture destinationSlice:s destinationLevel:0
				destinationOrigin:MTLOriginMake(0, 0, 0)];
		}
		[staging release];
	}
}

id<MTLComputePipelineState> mc_api_compute_new(Ctx *ctx, id<MTLLibrary> library, const char *name, char *err, int errCap) {
	@autoreleasepool {
		id<MTLFunction> f = [[library newFunctionWithName:[NSString stringWithUTF8String:name]] autorelease];
		if (!f) {
			copyApiError(nil, err, errCap);
			return nil;
		}
		NSError *e = nil;
		id<MTLComputePipelineState> pso = [ctx->device newComputePipelineStateWithFunction:f error:&e];
		if (!pso) copyApiError(e, err, errCap);
		return pso;
	}
}

id<MTLComputeCommandEncoder> mc_api_compute_begin(Enc *enc) {
	@autoreleasepool {
		return [[mc_frame_cmd(enc) computeCommandEncoder] retain];
	}
}

void mc_api_compute_end(id<MTLComputeCommandEncoder> c) {
	[c endEncoding];
	[c release];
}

void mc_api_c_pipeline(id<MTLComputeCommandEncoder> c, id<MTLComputePipelineState> pso) { [c setComputePipelineState:pso]; }
void mc_api_c_buffer(id<MTLComputeCommandEncoder> c, int index, id<MTLBuffer> buffer, uint64_t offset) { [c setBuffer:buffer offset:offset atIndex:index]; }
void mc_api_c_bytes(id<MTLComputeCommandEncoder> c, int index, const void *bytes, int length) { [c setBytes:bytes length:length atIndex:index]; }

void mc_api_c_texture(id<MTLComputeCommandEncoder> c, int index, id<MTLTexture> texture, id<MTLSamplerState> sampler) {
	[c setTexture:texture atIndex:index];
	if (sampler) [c setSamplerState:sampler atIndex:index];
}

void mc_api_c_dispatch(id<MTLComputeCommandEncoder> c, int x, int y, int z, int lx, int ly, int lz) {
	[c dispatchThreadgroups:MTLSizeMake(x, y, z) threadsPerThreadgroup:MTLSizeMake(lx, ly, lz)];
}

void mc_api_c_dispatch_indirect(id<MTLComputeCommandEncoder> c, id<MTLBuffer> buffer, uint64_t offset, int lx, int ly, int lz) {
	[c dispatchThreadgroupsWithIndirectBuffer:buffer indirectBufferOffset:offset threadsPerThreadgroup:MTLSizeMake(lx, ly, lz)];
}

void mc_api_r_stage_texture(Enc *enc, int stage, int index, id<MTLTexture> texture, id<MTLSamplerState> sampler) {
	if (!enc->render) return;
	if (stage & 1) {
		[enc->render setVertexTexture:texture atIndex:index];
		if (sampler) [enc->render setVertexSamplerState:sampler atIndex:index];
	}
	if (stage & 2) {
		[enc->render setFragmentTexture:texture atIndex:index];
		if (sampler) [enc->render setFragmentSamplerState:sampler atIndex:index];
	}
}

void mc_api_r_stage_buffer(Enc *enc, int stage, int index, id<MTLBuffer> buffer, uint64_t offset) {
	if (!enc->render) return;
	if (stage & 1) [enc->render setVertexBuffer:buffer offset:offset atIndex:index];
	if (stage & 2) [enc->render setFragmentBuffer:buffer offset:offset atIndex:index];
}

void mc_api_r_stage_bytes(Enc *enc, int stage, int index, const void *bytes, int length) {
	if (!enc->render) return;
	if (stage & 1) [enc->render setVertexBytes:bytes length:length atIndex:index];
	if (stage & 2) [enc->render setFragmentBytes:bytes length:length atIndex:index];
}
