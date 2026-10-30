package mcopt.metal.shaderpack.render;

import mcopt.metal.PipelineInfo;
import org.jspecify.annotations.Nullable;

final class Programs {
	private Programs() {
	}

	static @Nullable String program(PipelineInfo info, boolean hand) {
		String n = info.name();
		boolean translucent = info.blend() != null || n.contains("translucent");
		if (n.startsWith("sodium:")) {
			if (n.contains("translucent")) return hand ? "gbuffers_hand_water" : "gbuffers_water";
			return n.contains("cutout") ? "gbuffers_terrain_cutout" : "gbuffers_terrain_solid";
		}
		if (n.contains("water_mask") || n.contains("oit") || n.contains("debug")) return null;
		if (hand) return translucent ? "gbuffers_hand_water" : "gbuffers_hand";
		if (n.contains("end_sky") || n.contains("celestial") || n.contains("sun") || n.contains("moon")) return "gbuffers_skytextured";
		if (n.contains("sky") || n.contains("stars")) return "gbuffers_skybasic";
		if (n.contains("cloud")) return "gbuffers_clouds";
		if (n.contains("weather") || n.contains("rain") || n.contains("snow")) return "gbuffers_weather";
		if (n.contains("particle")) return translucent ? "gbuffers_particles_translucent" : "gbuffers_particles";
		if (n.contains("lightning")) return "gbuffers_lightning";
		if (n.contains("beacon")) return "gbuffers_beaconbeam";
		if (n.contains("glint")) return "gbuffers_armor_glint";
		if (n.contains("eyes")) return "gbuffers_spidereyes";
		if (n.contains("crumbling")) return "gbuffers_damagedblock";
		if (n.contains("line")) return "gbuffers_line";
		if (n.contains("world_border") || n.contains("leash")) return "gbuffers_textured";
		if (n.contains("entity") || n.contains("item") || n.contains("armor") || n.contains("banner") || n.contains("block") || n.contains("text")) {
			return translucent ? "gbuffers_entities_translucent" : "gbuffers_entities";
		}
		boolean textured = false;
		for (PipelineInfo.Attrib a : info.attribs()) textured |= a.name().equals("UV0");
		return textured ? "gbuffers_textured" : "gbuffers_basic";
	}
}
