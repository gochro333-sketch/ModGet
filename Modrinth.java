package com.modget;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Small Modrinth API client plus the file handling for installing and updating mod jars. */
final class Modrinth {
	private static final String API = "https://api.modrinth.com/v2";
	private static final String LOADER = "fabric";
	private static final String UA = "modget-fabric/1.0.0 (personal use)";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	record Download(String name, byte[] data) {}

	private final String gameVersion;
	private final Path modsDir;
	private final Path backupDir;

	Modrinth(String gameVersion, Path modsDir, Path backupDir) {
		this.gameVersion = gameVersion;
		this.modsDir = modsDir;
		this.backupDir = backupDir;
	}

	String gameVersion() {
		return gameVersion;
	}

	// ---------- HTTP ----------

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static JsonElement getJson(String url) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", UA)
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			throw new IOException("Modrinth returned HTTP " + res.statusCode());
		}
		return JsonParser.parseString(res.body());
	}

	private static JsonElement postJson(String url, JsonObject body) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", UA)
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			throw new IOException("Modrinth returned HTTP " + res.statusCode());
		}
		return JsonParser.parseString(res.body());
	}

	// ---------- hashing ----------

	static String sha512(byte[] data) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-512").digest(data);
			StringBuilder sb = new StringBuilder(d.length * 2);
			for (byte b : d) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---------- queries ----------

	/** Search for Fabric mods that support this Minecraft version. */
	List<JsonObject> search(String query) throws IOException, InterruptedException {
		String facets = "[[\"project_type:mod\"],[\"categories:" + LOADER + "\"],[\"versions:" + gameVersion + "\"]]";
		String url = API + "/search?query=" + enc(query) + "&limit=8&facets=" + enc(facets);
		JsonArray hits = getJson(url).getAsJsonObject().getAsJsonArray("hits");
		List<JsonObject> out = new ArrayList<>();
		for (JsonElement e : hits) {
			out.add(e.getAsJsonObject());
		}
		return out;
	}

	/** Newest release (or newest anything, if there is no release) for this game version and Fabric. */
	JsonObject latest(String projectIdOrSlug) throws IOException, InterruptedException {
		String url = API + "/project/" + enc(projectIdOrSlug) + "/version"
				+ "?loaders=" + enc("[\"" + LOADER + "\"]")
				+ "&game_versions=" + enc("[\"" + gameVersion + "\"]");
		JsonArray versions = getJson(url).getAsJsonArray();
		if (versions.isEmpty()) {
			return null;
		}
		for (JsonElement e : versions) {
			JsonObject v = e.getAsJsonObject();
			if ("release".equals(v.get("version_type").getAsString())) {
				return v;
			}
		}
		return versions.get(0).getAsJsonObject();
	}

	static JsonObject primaryFile(JsonObject version) {
		JsonArray files = version.getAsJsonArray("files");
		for (JsonElement e : files) {
			JsonObject f = e.getAsJsonObject();
			if (f.has("primary") && f.get("primary").getAsBoolean()) {
				return f;
			}
		}
		return files.get(0).getAsJsonObject();
	}

	// ---------- installed jars ----------

	/** sha512 -> jar path, for every .jar directly in the mods folder. */
	Map<String, Path> installedHashes() throws IOException {
		Map<String, Path> out = new HashMap<>();
		if (!Files.isDirectory(modsDir)) {
			return out;
		}
		try (Stream<Path> s = Files.list(modsDir)) {
			for (Path p : (Iterable<Path>) s::iterator) {
				if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
					out.put(sha512(Files.readAllBytes(p)), p);
				}
			}
		}
		return out;
	}

	private static JsonArray toArray(Collection<String> items) {
		JsonArray a = new JsonArray();
		for (String s : items) {
			a.add(s);
		}
		return a;
	}

	/** Modrinth project ids of mods already in the mods folder (matched by file hash). */
	Set<String> installedProjects(Collection<String> hashes) throws IOException, InterruptedException {
		Set<String> out = new HashSet<>();
		if (hashes.isEmpty()) {
			return out;
		}
		JsonObject body = new JsonObject();
		body.add("hashes", toArray(hashes));
		body.addProperty("algorithm", "sha512");
		JsonObject res = postJson(API + "/version_files", body).getAsJsonObject();
		for (Map.Entry<String, JsonElement> e : res.entrySet()) {
			out.add(e.getValue().getAsJsonObject().get("project_id").getAsString());
		}
		return out;
	}

	/** old sha512 -> newest compatible version, for each installed jar Modrinth knows about. */
	JsonObject latestForHashes(Collection<String> hashes) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.add("hashes", toArray(hashes));
		body.addProperty("algorithm", "sha512");
		body.add("loaders", toArray(List.of(LOADER)));
		body.add("game_versions", toArray(List.of(gameVersion)));
		return postJson(API + "/version_files/update", body).getAsJsonObject();
	}

	// ---------- download + write ----------

	/** Downloads a file, checking it comes from Modrinth's CDN and matches the published SHA-512. */
	Download fetch(JsonObject file) throws IOException, InterruptedException {
		URI uri = URI.create(file.get("url").getAsString());
		if (!"https".equals(uri.getScheme()) || !"cdn.modrinth.com".equals(uri.getHost())) {
			throw new IOException("Refusing to download from " + uri.getHost());
		}
		String name = Path.of(file.get("filename").getAsString()).getFileName().toString();
		if (!name.endsWith(".jar") || name.startsWith(".") || !name.matches("[A-Za-z0-9._+() \\-]+")) {
			throw new IOException("Unsafe file name: " + name);
		}
		String expected = file.getAsJsonObject("hashes").get("sha512").getAsString();

		HttpRequest req = HttpRequest.newBuilder(uri)
				.header("User-Agent", UA)
				.timeout(Duration.ofMinutes(2))
				.GET()
				.build();
		HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (res.statusCode() != 200) {
			throw new IOException("Download failed: HTTP " + res.statusCode());
		}
		if (!sha512(res.body()).equalsIgnoreCase(expected)) {
			throw new IOException("Download did not match its checksum, discarded");
		}
		return new Download(name, res.body());
	}

	Path write(Download d) throws IOException {
		Files.createDirectories(modsDir);
		Path dest = modsDir.resolve(d.name());
		Path tmp = modsDir.resolve(d.name() + ".part");
		Files.write(tmp, d.data());
		Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
		return dest;
	}

	/** Moves an old jar out of the mods folder (so it stops loading) into modget-backup. */
	void backup(Path old) throws IOException {
		Files.createDirectories(backupDir);
		Path dest = backupDir.resolve(old.getFileName().toString() + "." + System.currentTimeMillis());
		Files.move(old, dest, StandardCopyOption.REPLACE_EXISTING);
	}
}
