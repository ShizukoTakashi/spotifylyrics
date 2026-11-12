package dev.spotifylyrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** User settings, stored in config/spotifylyrics.json. Everything here is editable from the in-game settings screen. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Client ID of your own app from developer.spotify.com/dashboard (no secret needed). */
	public String clientId = "";

	public boolean lyricsEnabled = true;
	/** Id from Fonts.ALL. */
	public String font = "poppins_bold";
	/** RGB colour of the line being sung. */
	public int activeColor = 0x1ED760;
	/** RGB colour of the previous / next lines. */
	public int otherColor = 0xC8C8C8;
	public float scale = 1.0f;
	/** 0 = no panel, 1 = soft panel, 2 = solid panel. */
	public int panelStyle = 1;
	/** 1, 3 or 5 lines visible. */
	public int linesShown = 3;
	public boolean showTitle = true;
	public boolean showProgress = true;
	public boolean boldActive = false;
	/** Positive = show lines earlier (compensates for network delay). */
	public int lyricsOffsetMs = 300;
	/** Distance in GUI pixels between the bottom of the screen and the lyric block. */
	public int hudBottomOffset = 70;

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("spotifylyrics.json");
	}

	public static Config load() {
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				Config c = GSON.fromJson(r, Config.class);
				if (c != null) {
					c.sanitize();
					return c;
				}
			} catch (Exception e) {
				SpotifyLyricsMod.LOGGER.warn("Could not read config, using defaults", e);
			}
		}
		Config c = new Config();
		c.save();
		return c;
	}

	private void sanitize() {
		if (clientId == null) clientId = "";
		if (font == null) font = "poppins_bold";
		scale = Math.max(0.6f, Math.min(2.5f, scale));
		panelStyle = Math.max(0, Math.min(2, panelStyle));
		if (linesShown != 1 && linesShown != 3 && linesShown != 5) linesShown = 3;
		hudBottomOffset = Math.max(0, Math.min(400, hudBottomOffset));
	}

	public synchronized void save() {
		try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
			GSON.toJson(this, w);
		} catch (IOException e) {
			SpotifyLyricsMod.LOGGER.warn("Could not save config", e);
		}
	}
}
