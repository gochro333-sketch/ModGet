package com.modget;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** The install/update/search logic, with no UI. All methods block, so call them off the game thread. */
final class Actions {
	static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "modget-worker");
		t.setDaemon(true);
		return t;
	});

	record Hit(String slug, String title, String description) {}

	private final Modrinth api;

	Actions(Modrinth api) {
		this.api = api;
	}

	String gameVersion() {
		return api.gameVersion();
	}

	List<Hit> search(String query) throws IOException, InterruptedException {
		List<Hit> out = new ArrayList<>();
		for (JsonObject h : api.search(query)) {
			out.add(new Hit(
					h.get("slug").getAsString(),
					h.get("title").getAsString(),
					h.get("description").getAsString()));
		}
		return out;
	}

	void install(String slug, boolean live, Consumer<String> say) throws IOException, InterruptedException {
		Set<String> have = api.installedProjects(api.installedHashes().keySet());
		List<Path> installed = new ArrayList<>();
		installRec(slug, have, 0, say, installed);
		if (installed.isEmpty()) {
			return;
		}
		if (!live) {
			say.accept("Installed. Restart the game to load it.");
			return;
		}
		// Dependencies were added after the mod that needs them, so start them first.
		for (int i = installed.size() - 1; i >= 0; i--) {
			say.accept(LiveLoader.tryLoad(installed.get(i)));
		}
	}

	private void installRec(String idOrSlug, Set<String> have, int depth, Consumer<String> say, List<Path> installed)
			throws IOException, InterruptedException {
		JsonObject version = api.latest(idOrSlug);
		if (version == null) {
			say.accept("No Fabric build of " + idOrSlug + " for " + api.gameVersion());
			return;
		}
		String projectId = version.get("project_id").getAsString();
		if (have.contains(projectId)) {
			if (depth == 0) {
				say.accept(idOrSlug + " is already installed. Use Update all.");
			}
			return;
		}
		say.accept("Downloading " + idOrSlug + "...");
		Path written = api.write(api.fetch(Modrinth.primaryFile(version)));
		have.add(projectId);
		installed.add(written);
		say.accept("Downloaded " + written.getFileName());

		if (depth >= 3) {
			return;
		}
		for (JsonElement e : version.getAsJsonArray("dependencies")) {
			JsonObject dep = e.getAsJsonObject();
			boolean required = "required".equals(dep.get("dependency_type").getAsString());
			if (required && dep.has("project_id") && !dep.get("project_id").isJsonNull()) {
				installRec(dep.get("project_id").getAsString(), have, depth + 1, say, installed);
			}
		}
	}

	void update(Consumer<String> say) throws IOException, InterruptedException {
		Map<String, Path> hashes = api.installedHashes();
		if (hashes.isEmpty()) {
			say.accept("No mods found in the mods folder.");
			return;
		}
		say.accept("Checking " + hashes.size() + " mods...");
		JsonObject latest = api.latestForHashes(hashes.keySet());
		int updated = 0;
		for (Map.Entry<String, JsonElement> entry : latest.entrySet()) {
			String oldHash = entry.getKey();
			JsonObject file = Modrinth.primaryFile(entry.getValue().getAsJsonObject());
			String newHash = file.getAsJsonObject("hashes").get("sha512").getAsString();
			Path old = hashes.get(oldHash);
			if (old == null || newHash.equalsIgnoreCase(oldHash)) {
				continue;
			}
			try {
				Modrinth.Download download = api.fetch(file);
				api.backup(old);
				api.write(download);
				updated++;
				say.accept("Updated " + old.getFileName());
			} catch (IOException e) {
				say.accept("Could not update " + old.getFileName() + ": " + e.getMessage());
			}
		}
		say.accept(updated == 0
				? "Everything is up to date."
				: updated + " updated. Restart the game to apply.");
	}
}
