package com.modget;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;

/** Press the ModGet key (default M, changeable in Controls) to open the install/update window. */
public class ModGetClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricLoader loader = FabricLoader.getInstance();
		String gameVersion = loader.getModContainer("minecraft")
				.orElseThrow()
				.getMetadata()
				.getVersion()
				.getFriendlyString();
		Path gameDir = loader.getGameDir();
		Actions actions = new Actions(
				new Modrinth(gameVersion, gameDir.resolve("mods"), gameDir.resolve("modget-backup")));

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath("modget", "main"));
		KeyMapping openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.modget.open",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_M,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				client.gui.setScreen(new ModGetScreen(actions));
			}
		});
	}
}
