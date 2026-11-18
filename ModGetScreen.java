package com.modget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** The ModGet window: search Modrinth, tap a result to install it, or update everything. */
final class ModGetScreen extends Screen {
	private static final int ROWS = 5;

	private static boolean liveLoad = true;

	private final Actions actions;

	// Kept in fields so the window survives being resized (init() runs again).
	private String lastQuery = "";
	private String statusText = "Type a mod name, then tap Search.";
	private List<Actions.Hit> hits = List.of();

	private EditBox searchBox;
	private StringWidget status;
	private final Button[] rows = new Button[ROWS];

	ModGetScreen(Actions actions) {
		super(Component.literal("ModGet"));
		this.actions = actions;
	}

	@Override
	protected void init() {
		int w = Math.min(320, this.width - 20);
		int left = (this.width - w) / 2;
		int y = 16;

		this.addRenderableWidget(new StringWidget(left, y, w, 9,
				Component.literal("ModGet - Modrinth mods for " + actions.gameVersion()), this.font));
		y += 16;

		searchBox = new EditBox(this.font, left, y, w - 70, 20, Component.literal("Search"));
		searchBox.setHint(Component.literal("Search mods..."));
		searchBox.setMaxLength(60);
		searchBox.setValue(lastQuery);
		this.addRenderableWidget(searchBox);
		this.addRenderableWidget(Button.builder(Component.literal("Search"), b -> doSearch())
				.bounds(left + w - 66, y, 66, 20).build());
		y += 26;

		status = new StringWidget(left, y, w, 9, Component.literal(statusText), this.font);
		this.addRenderableWidget(status);
		y += 14;

		for (int i = 0; i < ROWS; i++) {
			final int index = i;
			rows[i] = Button.builder(Component.empty(), b -> doInstall(index))
					.bounds(left, y, w, 20).build();
			this.addRenderableWidget(rows[i]);
			y += 22;
		}
		refreshRows();
		y += 6;

		this.addRenderableWidget(Button.builder(liveLabel(), b -> {
			liveLoad = !liveLoad;
			b.setMessage(liveLabel());
		}).bounds(left, y, w, 20).build());
		y += 22;

		int half = (w - 4) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("Update all"), b -> doUpdate())
				.bounds(left, y, half, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
				.bounds(left + half + 4, y, half, 20).build());
	}

	private static Component liveLabel() {
		return Component.literal("Load new mods without restart: " + (liveLoad ? "ON" : "OFF"));
	}

	private void refreshRows() {
		for (int i = 0; i < ROWS; i++) {
			if (i < hits.size()) {
				Actions.Hit h = hits.get(i);
				rows[i].setMessage(Component.literal(h.title() + " (" + h.slug() + ")"));
				rows[i].visible = true;
			} else {
				rows[i].visible = false;
			}
		}
	}

	private void setStatus(String text) {
		if (text.length() > 70) {
			text = text.substring(0, 67) + "...";
		}
		statusText = text;
		if (status != null) {
			status.setMessage(Component.literal(text));
		}
	}

	/** Runs on the worker thread, then hands the result back to the game thread. */
	private static void post(Runnable r) {
		Minecraft.getInstance().execute(r);
	}

	private void doSearch() {
		String q = searchBox.getValue().trim();
		if (q.isEmpty()) {
			return;
		}
		lastQuery = q;
		setStatus("Searching...");
		Actions.WORKER.submit(() -> {
			try {
				List<Actions.Hit> found = actions.search(q);
				post(() -> {
					hits = found;
					refreshRows();
					setStatus(found.isEmpty() ? "No results for this version." : "Tap a mod to install it.");
				});
			} catch (Exception e) {
				post(() -> setStatus("Error: " + e.getMessage()));
			}
		});
	}

	private void doInstall(int index) {
		if (index >= hits.size()) {
			return;
		}
		String slug = hits.get(index).slug();
		Actions.WORKER.submit(() -> {
			try {
				actions.install(slug, liveLoad, msg -> post(() -> setStatus(msg)));
			} catch (Exception e) {
				post(() -> setStatus("Error: " + e.getMessage()));
			}
		});
	}

	private void doUpdate() {
		Actions.WORKER.submit(() -> {
			try {
				actions.update(msg -> post(() -> setStatus(msg)));
			} catch (Exception e) {
				post(() -> setStatus("Error: " + e.getMessage()));
			}
		});
	}
}
