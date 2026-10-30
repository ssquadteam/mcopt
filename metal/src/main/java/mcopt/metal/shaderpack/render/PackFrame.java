package mcopt.metal.shaderpack.render;

import mcopt.metal.shaderpack.PackUniforms;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

public final class PackFrame {
	private static final Matrix4f TO_GL = new Matrix4f().m22(-2).m32(1);
	final Matrix4f projection = new Matrix4f(), view = new Matrix4f(), previousProjection = new Matrix4f(), previousView = new Matrix4f();
	final Matrix4f shadowModelView = new Matrix4f(), shadowProjection = new Matrix4f();
	Vec3 camera = Vec3.ZERO, previousCamera = Vec3.ZERO;
	int frameCounter;
	private long lastNanos;
	private double frameTimeCounter;
	float frameTime;
	private float wetness;
	private float smoothBlock = -1, smoothSky = -1;
	private @Nullable Map<String, Integer> biomeIds;
	private @Nullable ClientLevel biomeLevel;
	boolean shadowsActive;
	private final float[] scratch = new float[16];

	void camera(Matrix4fc gameProjection, Matrix4fc viewRotation, Vec3 position) {
		this.previousProjection.set(this.projection);
		this.previousView.set(this.view);
		this.previousCamera = this.camera;
		TO_GL.mul(gameProjection, this.projection);
		this.view.set(viewRotation);
		this.camera = position;
		long now = System.nanoTime();
		this.frameTime = this.lastNanos == 0 ? 0 : Math.min(1, (now - this.lastNanos) / 1e9F);
		this.lastNanos = now;
		this.frameTimeCounter = (this.frameTimeCounter + this.frameTime) % 3600;
		this.frameCounter = (this.frameCounter + 1) % 720720;
	}

	void update(PackUniforms u, int width, int height, Map<String, String> consts) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		u.frameTime(this.frameTime);
		u.get("viewWidth").put(width);
		u.get("viewHeight").put(height);
		u.get("aspectRatio").put(width / (double) height);
		u.get("near").put(0.05);
		u.get("far").put(mc.options.getEffectiveRenderDistance() * 16.0);
		u.get("frameTime").put(this.frameTime);
		u.get("frameTimeCounter").put(this.frameTimeCounter);
		u.get("frameCounter").put(this.frameCounter);
		u.get("screenBrightness").put(mc.options.gamma().get());
		u.get("gbufferModelView").matrix(this.view.get(this.scratch));
		u.get("gbufferModelViewInverse").matrix(new Matrix4f(this.view).invert().get(this.scratch));
		u.get("gbufferProjection").matrix(this.projection.get(this.scratch));
		u.get("gbufferProjectionInverse").matrix(new Matrix4f(this.projection).invert().get(this.scratch));
		u.get("gbufferPreviousModelView").matrix(this.previousView.get(this.scratch));
		u.get("gbufferPreviousProjection").matrix(this.previousProjection.get(this.scratch));
		camera(u, "cameraPosition", this.camera);
		camera(u, "previousCameraPosition", this.previousCamera);
		u.get("eyeAltitude").put(this.camera.y);
		u.get("renderStage").put(0);
		u.get("entityId").put(-1);
		u.get("blockEntityId").put(-1);
		u.get("currentRenderedItemId").put(-1);
		u.get("heldItemId").put(-1);
		u.get("heldItemId2").put(-1);
		u.get("entityColor").put(0, 0, 0, 0);
		u.get("atlasSize").put(1024, 1024);
		u.get("hideGUI").put(0);
		u.get("isRightHanded").put(1);
		long millis = System.currentTimeMillis();
		java.time.LocalDateTime now = java.time.LocalDateTime.now();
		u.get("currentDate").put(now.getYear(), now.getMonthValue(), now.getDayOfMonth());
		u.get("currentTime").put(now.getHour(), now.getMinute(), now.getSecond());
		u.get("currentYearTime").put((now.getDayOfYear() - 1) * 86400.0 + now.toLocalTime().toSecondOfDay(), 365 * 86400.0 - ((now.getDayOfYear() - 1) * 86400.0 + now.toLocalTime().toSecondOfDay()));
		u.get("systemTimeMillis").put(millis % 1000000);
		if (level == null) return;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		long time = level.getDefaultClockTime();
		u.get("worldTime").put(Math.floorMod(time, 24000L));
		u.get("worldDay").put(Math.floorDiv(time, 24000L));
		var attributes = level.environmentAttributes();
		float skyAngle = number(attributes.getValue(EnvironmentAttributes.SUN_ANGLE, this.camera), 0) / 360F;
		skyAngle -= (float) Math.floor(skyAngle);
		float sunAngle = skyAngle < 0.75F ? skyAngle + 0.25F : skyAngle - 0.75F;
		u.get("sunAngle").put(sunAngle);
		float shadowAngle = sunAngle <= 0.5F ? sunAngle : sunAngle - 0.5F;
		u.get("shadowAngle").put(shadowAngle);
		Object moon = attributes.getValue(EnvironmentAttributes.MOON_PHASE, this.camera);
		u.get("moonPhase").put(moon instanceof Enum<?> e ? e.ordinal() : 0);
		float sunPath = constFloat(consts, "sunPathRotation", 0);
		Vector4f sun = celestial(skyAngle, sunPath, 100), moonPos = celestial(skyAngle, sunPath, -100);
		u.get("sunPosition").put(sun.x, sun.y, sun.z);
		u.get("moonPosition").put(moonPos.x, moonPos.y, moonPos.z);
		Vector4f light = sunAngle <= 0.5F ? sun : moonPos;
		u.get("shadowLightPosition").put(light.x, light.y, light.z);
		Vector4f up = new Matrix4f(this.view).transform(new Vector4f(0, 100, 0, 0));
		u.get("upPosition").put(up.x, up.y, up.z);
		float rain = level.getRainLevel(partial);
		u.get("rainStrength").put(rain);
		u.get("thunderStrength").put(level.getThunderLevel(partial));
		float half = rain > this.wetness ? constFloat(consts, "wetnessHalflife", 600) : constFloat(consts, "drynessHalflife", 200);
		this.wetness += (rain - this.wetness) * (1 - (float) Math.pow(0.5, this.frameTime / Math.max(half / 20F, 1e-3F)));
		u.get("wetness").put(this.wetness);
		color(u.get("skyColor"), attributes.getValue(EnvironmentAttributes.SKY_COLOR, this.camera));
		color(u.get("fogColor"), attributes.getValue(EnvironmentAttributes.FOG_COLOR, this.camera));
		float fogStart = number(attributes.getValue(EnvironmentAttributes.FOG_START_DISTANCE, this.camera), 0);
		float fogEnd = number(attributes.getValue(EnvironmentAttributes.FOG_END_DISTANCE, this.camera), mc.options.getEffectiveRenderDistance() * 16F);
		u.get("fogStart").put(fogStart);
		u.get("fogEnd").put(fogEnd);
		u.get("fogDensity").put(0.5);
		u.get("fogMode").put(9729);
		u.get("fogShape").put(1);
		u.get("cloudHeight").put(number(attributes.getValue(EnvironmentAttributes.CLOUD_HEIGHT, this.camera), 192));
		BlockPos eye = BlockPos.containing(this.camera);
		int block = level.getBrightness(LightLayer.BLOCK, eye), sky = level.getBrightness(LightLayer.SKY, eye);
		u.get("eyeBrightness").put(block * 16, sky * 16);
		float k = 1 - (float) Math.pow(0.5, this.frameTime / constFloat(consts, "eyeBrightnessHalflife", 10));
		if (this.smoothBlock < 0) {
			this.smoothBlock = block * 16;
			this.smoothSky = sky * 16;
		}
		this.smoothBlock += (block * 16 - this.smoothBlock) * k;
		this.smoothSky += (sky * 16 - this.smoothSky) * k;
		u.get("eyeBrightnessSmooth").put(Math.round(this.smoothBlock), Math.round(this.smoothSky));
		u.get("lightningBoltPosition").put(0, 0, 0, 0);
		biome(u, level, eye);
		u.get("isEyeInWater").put(0);
		u.get("nightVision").put(0);
		u.get("blindness").put(0);
		u.get("darknessFactor").put(0);
		u.get("darknessLightFactor").put(0);
		if (player != null) {
			int inWater = player.isEyeInFluid(FluidTags.WATER) ? 1 : player.isEyeInFluid(FluidTags.LAVA) ? 2 : player.isInPowderSnow ? 3 : 0;
			u.get("isEyeInWater").put(inWater);
			u.get("nightVision").put(player.hasEffect(MobEffects.NIGHT_VISION) ? GameRenderer.nightVisionScale(player, partial) : 0F);
			MobEffectInstance blind = player.getEffect(MobEffects.BLINDNESS);
			u.get("blindness").put(blind == null ? 0F : blind.endsWithin(19) ? blind.getDuration() / 20F : 1F);
			u.get("darknessFactor").put(player.getEffect(MobEffects.DARKNESS) == null ? 0 : 1);
			u.get("playerMood").put(player.getCurrentMood());
			u.get("isElytraFlying").put(player.isFallFlying() ? 1 : 0);
			u.get("is_invisible").put(player.isInvisible() ? 1 : 0);
			u.get("is_sneaking").put(player.isCrouching() ? 1 : 0);
			u.get("is_sprinting").put(player.isSprinting() ? 1 : 0);
			u.get("is_on_ground").put(player.onGround() ? 1 : 0);
			u.get("is_burning").put(player.isOnFire() ? 1 : 0);
			u.get("currentPlayerHealth").put(player.getHealth() / Math.max(1, player.getMaxHealth()));
			u.get("maxPlayerHealth").put(player.getMaxHealth());
			u.get("currentPlayerAir").put(player.getAirSupply() / (double) Math.max(1, player.getMaxAirSupply()));
			u.get("currentPlayerHunger").put(player.getFoodData().getFoodLevel() / 20.0);
			Vec3 eyePos = player.getEyePosition(partial);
			u.get("eyePosition").put(eyePos.x, eyePos.y, eyePos.z);
			u.get("relativeEyePosition").put(eyePos.x - this.camera.x, eyePos.y - this.camera.y, eyePos.z - this.camera.z);
			Vec3 look = player.getViewVector(partial);
			u.get("playerLookVector").put(look.x, look.y, look.z);
			u.get("playerBodyVector").put(look.x, look.y, look.z);
		}
		shadow(u, shadowAngle, sunPath, consts);
	}

	private static void camera(PackUniforms u, String name, Vec3 p) {
		u.get(name).put(p.x, p.y, p.z);
		double x = Math.floor(p.x), y = Math.floor(p.y), z = Math.floor(p.z);
		u.get(name + "Int").put(x, y, z);
		u.get(name + "Fract").put(p.x - x, p.y - y, p.z - z);
	}

	private Vector4f celestial(float skyAngle, float sunPath, float y) {
		Matrix4f m = new Matrix4f(this.view).rotateY((float) Math.toRadians(-90)).rotateZ((float) Math.toRadians(sunPath)).rotateX((float) Math.toRadians(skyAngle * 360));
		return m.transform(new Vector4f(0, y, 0, 0));
	}

	private void shadow(PackUniforms u, float shadowAngle, float sunPath, Map<String, String> consts) {
		float distance = constFloat(consts, "shadowDistance", 160);
		float interval = constFloat(consts, "shadowIntervalSize", 2);
		float skyAngle = shadowAngle < 0.25F ? shadowAngle + 0.75F : shadowAngle - 0.25F;
		Matrix4f mv = this.shadowModelView.identity().translate(0, 0, -100).rotateX((float) Math.toRadians(90)).rotateZ((float) Math.toRadians(skyAngle * -360))
			.rotateX((float) Math.toRadians(sunPath));
		if (interval > 0) {
			float half = interval / 2;
			mv.translate((float) (this.camera.x % interval) - half, (float) (this.camera.y % interval) - half, (float) (this.camera.z % interval) - half);
		}
		Matrix4f proj = this.shadowProjection.setOrtho(-distance, distance, -distance, distance, 0.05F, 256);
		u.get("shadowModelView").matrix(mv.get(this.scratch));
		u.get("shadowModelViewInverse").matrix(new Matrix4f(mv).invert().get(this.scratch));
		u.get("shadowProjection").matrix(proj.get(this.scratch));
		u.get("shadowProjectionInverse").matrix(new Matrix4f(proj).invert().get(this.scratch));
	}

	private void biome(PackUniforms u, ClientLevel level, BlockPos pos) {
		Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
		if (this.biomeIds == null || this.biomeLevel != level) {
			Map<String, Integer> ids = new HashMap<>();
			for (Biome b : registry) {
				Identifier key = registry.getKey(b);
				if (key != null) ids.put("BIOME_" + key.getPath().toUpperCase(Locale.ROOT), registry.getId(b));
			}
			this.biomeIds = ids;
			this.biomeLevel = level;
		}
		for (var e : this.biomeIds.entrySet()) u.get(e.getKey()).put(e.getValue());
		Holder<Biome> holder = level.getBiome(pos);
		u.get("biome").put(registry.getId(holder.value()));
		u.get("biome_precipitation").put(holder.value().getPrecipitationAt(pos, level.getSeaLevel()).ordinal());
		u.get("temperature").put(holder.value().getBaseTemperature());
		u.get("rainfall").put(0.5);
		u.get("PPT_NONE").put(0);
		u.get("PPT_RAIN").put(1);
		u.get("PPT_SNOW").put(2);
	}

	private static float number(@Nullable Object o, float fallback) {
		return o instanceof Number n ? n.floatValue() : fallback;
	}

	private static void color(PackUniforms.Value v, @Nullable Object o) {
		if (o instanceof org.joml.Vector3fc c) v.put(c.x(), c.y(), c.z());
		else if (o instanceof org.joml.Vector4fc c) v.put(c.x(), c.y(), c.z());
		else if (o instanceof Integer argb) v.put((argb >> 16 & 255) / 255.0, (argb >> 8 & 255) / 255.0, (argb & 255) / 255.0);
		else v.put(0, 0, 0);
	}

	static float constFloat(Map<String, String> consts, String name, float fallback) {
		String s = consts.get(name);
		if (s == null) return fallback;
		try {
			return Float.parseFloat(s.strip().replaceFirst("[fF]$", ""));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
