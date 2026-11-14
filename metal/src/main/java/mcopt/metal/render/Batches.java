package mcopt.metal.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * -Dmcopt.render.batchOrder: a feature's batches (a HashMap keyed by render type, so their order was arbitrary to begin with)
 * are drawn opaque first, grouped by pipeline in first-seen order, then the blended ones in their original order. Every
 * group then binds its pipeline once. When the opaque batches already come grouped nothing is copied or allocated.
 */
public final class Batches {
	private static final ArrayList<Object> ordered = new ArrayList<>();
	private static final ArrayList<Object> blended = new ArrayList<>();
	private static final IdentityHashMap<Object, ArrayList<Object>> byPipeline = new IdentityHashMap<>();
	private static final ArrayList<Object> pipelines = new ArrayList<>();
	private static Thread owner;

	private Batches() {
	}

	@SuppressWarnings("unchecked")
	public static <V> Collection<V> order(Map<?, V> batches, Collection<V> values) {
		if (batches.size() < 3) return values;
		if (owner == null) owner = Thread.currentThread();
		else if (owner != Thread.currentThread()) return values;
		if (grouped(batches)) return values;
		ordered.clear();
		blended.clear();
		pipelines.clear();
		for (Map.Entry<?, V> e : batches.entrySet()) {
			if (e.getKey() instanceof RenderType type && !type.hasBlending()) {
				Object pipeline = type.pipeline();
				ArrayList<Object> group = byPipeline.get(pipeline);
				if (group == null) {
					group = new ArrayList<>();
					byPipeline.put(pipeline, group);
				}
				if (group.isEmpty()) pipelines.add(pipeline);
				group.add(e.getValue());
			} else {
				blended.add(e.getValue());
			}
		}
		for (Object pipeline : pipelines) {
			ArrayList<Object> group = byPipeline.get(pipeline);
			ordered.addAll(group);
			group.clear();
		}
		ordered.addAll(blended);
		blended.clear();
		if (byPipeline.size() > 256) byPipeline.clear();
		return (Collection<V>) ordered;
	}

	/** True when no opaque pipeline reappears after another one and no blended batch comes before an opaque one. */
	private static boolean grouped(Map<?, ?> batches) {
		pipelines.clear();
		Object last = null;
		boolean sawBlended = false;
		for (Object key : batches.keySet()) {
			if (!(key instanceof RenderType type) || type.hasBlending()) {
				sawBlended = true;
				continue;
			}
			if (sawBlended) return false;
			Object pipeline = type.pipeline();
			if (pipeline == last) continue;
			for (int i = 0, n = pipelines.size(); i < n; i++) {
				if (pipelines.get(i) == pipeline) return false;
			}
			pipelines.add(pipeline);
			last = pipeline;
		}
		return true;
	}
}
