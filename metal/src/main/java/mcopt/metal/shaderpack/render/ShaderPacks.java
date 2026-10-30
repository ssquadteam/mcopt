package mcopt.metal.shaderpack.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import mcopt.metal.Profile;

public final class ShaderPacks {
	private static final String KEY = "mcopt.pack";

	private ShaderPacks() {
	}

	public static List<String> available() {
		List<String> names = new ArrayList<>();
		try {
			Files.createDirectories(PackRuntime.directory());
			try (Stream<Path> files = Files.list(PackRuntime.directory())) {
				files.filter(ShaderPacks::isPack).map(p -> p.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).forEach(names::add);
			}
		} catch (IOException e) {
			System.out.println("[mcopt] shaderpack: can't list " + PackRuntime.directory() + ": " + e);
		}
		return names;
	}

	private static boolean isPack(Path p) {
		String n = p.getFileName().toString();
		if (Files.isDirectory(p)) return Files.isDirectory(p.resolve("shaders"));
		return n.endsWith(".zip");
	}

	public static String selected() {
		return System.getProperty(KEY, "");
	}

	public static void select(String name) {
		Profile.save(KEY, name);
		System.setProperty(KEY, name);
		PackRuntime.reload();
	}
}
