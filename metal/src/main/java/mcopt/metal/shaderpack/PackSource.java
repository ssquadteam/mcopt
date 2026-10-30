package mcopt.metal.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

public final class PackSource implements AutoCloseable {
	private final String name;
	private final @Nullable Path directory;
	private final @Nullable ZipFile zip;
	private final String root;
	private final List<String> files;
	private final Map<String, String> text = new ConcurrentHashMap<>();

	private PackSource(String name, @Nullable Path directory, @Nullable ZipFile zip, String root, List<String> files) {
		this.name = name;
		this.directory = directory;
		this.zip = zip;
		this.root = root;
		this.files = files;
	}

	public static PackSource open(Path path) throws IOException {
		String name = path.getFileName().toString();
		if (Files.isDirectory(path)) {
			Path shaders = path.resolve("shaders");
			if (!Files.isDirectory(shaders)) throw new IOException(name + " has no shaders folder");
			List<String> files = new ArrayList<>();
			try (Stream<Path> walk = Files.walk(shaders)) {
				walk.filter(Files::isRegularFile).forEach(p -> files.add(shaders.relativize(p).toString().replace('\\', '/')));
			}
			Collections.sort(files);
			return new PackSource(name, shaders, null, "", files);
		}
		ZipFile zip = new ZipFile(path.toFile());
		String root = null;
		List<ZipEntry> entries = Collections.list(zip.entries()).stream().map(e -> (ZipEntry) e).toList();
		for (ZipEntry e : entries) {
			String n = e.getName();
			int at = n.indexOf("shaders/");
			if (at >= 0 && (at == 0 || n.charAt(at - 1) == '/') && (root == null || at < root.length())) root = n.substring(0, at + 8);
		}
		if (root == null) {
			zip.close();
			throw new IOException(name + " has no shaders folder");
		}
		List<String> files = new ArrayList<>();
		for (ZipEntry e : entries) if (!e.isDirectory() && e.getName().startsWith(root)) files.add(e.getName().substring(root.length()));
		Collections.sort(files);
		return new PackSource(name, null, zip, root, files);
	}

	public String name() {
		return this.name;
	}

	public List<String> files() {
		return this.files;
	}

	public boolean exists(String path) {
		return Collections.binarySearch(this.files, normalize(path)) >= 0;
	}

	public synchronized @Nullable String read(String path) throws IOException {
		String p = normalize(path);
		String cached = this.text.get(p);
		if (cached != null) return cached;
		byte[] bytes = bytes(p);
		if (bytes == null) return null;
		String s = new String(bytes, StandardCharsets.UTF_8);
		if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
		s = s.replace("\r\n", "\n").replace('\r', '\n');
		this.text.put(p, s);
		return s;
	}

	public synchronized byte @Nullable [] bytes(String path) throws IOException {
		String p = normalize(path);
		if (!exists(p)) return null;
		if (this.directory != null) return Files.readAllBytes(this.directory.resolve(p));
		ZipEntry e = this.zip.getEntry(this.root + p);
		if (e == null) return null;
		try (InputStream in = this.zip.getInputStream(e)) {
			return in.readAllBytes();
		}
	}

	public static String normalize(String path) {
		String p = path.replace('\\', '/');
		while (p.startsWith("/")) p = p.substring(1);
		if (!p.contains("..") && !p.contains("./") && !p.contains("//")) return p;
		List<String> parts = new ArrayList<>();
		for (String s : p.split("/")) {
			if (s.isEmpty() || s.equals(".")) continue;
			if (s.equals("..")) {
				if (!parts.isEmpty()) parts.removeLast();
			} else {
				parts.add(s);
			}
		}
		return String.join("/", parts);
	}

	public static String resolve(String from, String include) {
		if (include.startsWith("/")) return normalize(include);
		int slash = from.lastIndexOf('/');
		return normalize((slash < 0 ? "" : from.substring(0, slash + 1)) + include);
	}

	@Override
	public void close() throws IOException {
		if (this.zip != null) this.zip.close();
	}
}
