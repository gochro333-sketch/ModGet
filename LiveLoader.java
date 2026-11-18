package com.modget;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A deliberately small live loader. It can start a freshly downloaded mod without a restart, but ONLY when
 * that is safe: no mixins, no nested jars, no access wideners, all dependencies already running, and plain
 * Java entrypoints. Anything else is left for the next restart. It cannot unload or update a running mod.
 */
final class LiveLoader {
	private static final Set<String> LOADED_LIVE = new HashSet<>();
	private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader");

	private LiveLoader() {}

	/** Returns a short human-readable result. Never throws. */
	static String tryLoad(Path jar) {
		String name = jar.getFileName().toString();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return name + ": not a Fabric mod, restart to load";
			}
			JsonObject meta;
			try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
				meta = JsonParser.parseReader(reader).getAsJsonObject();
			}
			String id = meta.get("id").getAsString();
			if (FabricLoader.getInstance().isModLoaded(id) || LOADED_LIVE.contains(id)) {
				return id + ": already running";
			}

			String blocker = blocker(meta);
			if (blocker != null) {
				return id + ": restart needed (" + blocker + ")";
			}
			if (!meta.has("entrypoints")) {
				return id + ": installed, restart to load its assets";
			}

			addToClassPath(jar);
			String result = runEntrypoints(id, meta.getAsJsonObject("entrypoints"));
			LOADED_LIVE.add(id);
			return result;
		} catch (Throwable t) {
			return name + ": could not load live (" + t.getClass().getSimpleName() + "), restart to load";
		}
	}

	/** Why this mod can't be started live, or null if it can. */
	private static String blocker(JsonObject meta) {
		if (meta.has("mixins") && meta.getAsJsonArray("mixins").size() > 0) {
			return "uses mixins";
		}
		if (meta.has("jars") && meta.getAsJsonArray("jars").size() > 0) {
			return "has bundled libraries";
		}
		if (meta.has("accessWidener") || meta.has("classTweaker")) {
			return "uses access wideners";
		}
		if (meta.has("languageAdapter")) {
			return "uses a language adapter";
		}
		if (meta.has("entrypoints")) {
			JsonObject eps = meta.getAsJsonObject("entrypoints");
			for (String key : eps.keySet()) {
				if (!key.equals("main") && !key.equals("client") && !key.equals("server")) {
					return "has a '" + key + "' entrypoint";
				}
			}
		}
		if (meta.has("depends")) {
			for (Map.Entry<String, JsonElement> dep : meta.getAsJsonObject("depends").entrySet()) {
				String depId = dep.getKey();
				if (!BUILT_IN.contains(depId)
						&& !FabricLoader.getInstance().isModLoaded(depId)
						&& !LOADED_LIVE.contains(depId)) {
					return "needs " + depId + " running first";
				}
			}
		}
		return null;
	}

	/** Fabric Loader has no public API for this, so it is reached by reflection; failure just means "restart". */
	private static void addToClassPath(Path jar) throws Exception {
		Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
		Object launcher = base.getMethod("getLauncher").invoke(null);
		Class<?> iface = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher");
		Method add = iface.getMethod("addToClassPath", Path.class, String[].class);
		add.invoke(launcher, jar, new String[0]);
	}

	private static String runEntrypoints(String id, JsonObject eps) throws Exception {
		ClassLoader cl = LiveLoader.class.getClassLoader();
		int ran = 0;
		for (String key : new String[] {"main", "client"}) {
			if (!eps.has(key)) {
				continue;
			}
			for (JsonElement e : eps.getAsJsonArray(key)) {
				String className = e.isJsonObject() ? e.getAsJsonObject().get("value").getAsString() : e.getAsString();
				if (className.contains("::")) {
					throw new IllegalStateException("method entrypoint");
				}
				Object instance = Class.forName(className, true, cl).getDeclaredConstructor().newInstance();
				runOnGameThread(() -> {
					if (key.equals("main") && instance instanceof ModInitializer m) {
						m.onInitialize();
					} else if (key.equals("client") && instance instanceof ClientModInitializer c) {
						c.onInitializeClient();
					}
				});
				ran++;
			}
		}
		return id + ": loaded live (" + ran + " entrypoint" + (ran == 1 ? "" : "s") + ")"
				+ ", textures and lang files need a restart";
	}

	private static void runOnGameThread(Runnable r) throws Exception {
		CompletableFuture<Void> done = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				r.run();
				done.complete(null);
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		});
		done.get(30, TimeUnit.SECONDS);
	}
}
