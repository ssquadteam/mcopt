package mcopt.metal.render;

/** The -Dmcopt.render.* switches, kept free of game classes so the mixin plugin can read them before Minecraft loads. */
public final class RenderFlags {
	public static final boolean PARTICLE_CULL = Boolean.getBoolean("mcopt.render.particleCull");
	public static final boolean PARTICLE_LIGHT = Boolean.getBoolean("mcopt.render.particleLight");
	public static final boolean PARTICLE_EXTRACT = Boolean.getBoolean("mcopt.render.particleExtract");
	public static final boolean PARTICLE_WRITE = Boolean.getBoolean("mcopt.render.particleWrite");
	public static final boolean BATCH_ORDER = Boolean.getBoolean("mcopt.render.batchOrder");
	public static final boolean GUI_ITEMS = Boolean.getBoolean("mcopt.render.guiItems");
	public static final boolean TEXT_CACHE = Boolean.getBoolean("mcopt.render.textCache");
	public static final boolean ENTITY_REUSE = Boolean.getBoolean("mcopt.render.entityReuse");
	public static final boolean POSE_POOL = Boolean.getBoolean("mcopt.render.posePool");
	public static final boolean ASYNC_PIPELINES = Boolean.getBoolean("mcopt.render.asyncPipelines");

	private RenderFlags() {
	}
}
