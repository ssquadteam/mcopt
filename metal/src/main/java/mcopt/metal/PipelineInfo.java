package mcopt.metal;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record PipelineInfo(String name, List<Attrib> attribs, List<String> uniforms, int topologyClass, int primitive, boolean cull, boolean wireframe,
	boolean depthTest, int depthCompare, boolean depthWrite, float biasConstant, float biasSlope, int @Nullable [] blend, int writeMask) {
	public record Attrib(String name, int location, int bufferSlot, int offset, String format, int stride, int stepRate) {
	}

	public boolean usesBuffer0() {
		for (Attrib a : this.attribs) if (a.bufferSlot() == 0) return true;
		return false;
	}
}
