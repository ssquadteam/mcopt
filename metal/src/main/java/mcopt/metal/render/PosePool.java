package mcopt.metal.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * -Dmcopt.render.posePool: the pose copied for every submitted model, item, block and outline comes from two reusable sets
 * per submit collection instead of a new object each time. A set is handed out again only after the collection has been
 * drained twice, so every frame that read its poses has finished with them.
 */
public final class PosePool {
	private static final int MAX = 8192;

	public interface Owner {
		PosePool mcopt$poses();
	}

	private PoseStack.Pose[] current = new PoseStack.Pose[64];
	private PoseStack.Pose[] previous = new PoseStack.Pose[64];
	private int used;
	private @Nullable Thread thread;

	public PoseStack.Pose copy(PoseStack.Pose pose) {
		if (this.thread == null) this.thread = Thread.currentThread();
		if (this.thread != Thread.currentThread() || this.used == MAX) return pose.copy();
		if (this.used == this.current.length) this.current = Arrays.copyOf(this.current, this.used * 2);
		PoseStack.Pose kept = this.current[this.used];
		if (kept == null) {
			kept = pose.copy();
			this.current[this.used] = kept;
		} else {
			kept.set(pose);
		}
		this.used++;
		return kept;
	}

	public void drained() {
		PoseStack.Pose[] swap = this.previous;
		this.previous = this.current;
		this.current = swap;
		this.used = 0;
	}
}
