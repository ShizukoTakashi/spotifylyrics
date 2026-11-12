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

public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String clientId = "";
	public boolean lyricsEnabled = true;
	public String font = "poppins_bold";
	public int activeColor = 0xB388FF;
	public int otherColor = 0xE0D4FF;
	public float scale = 1.0f;
	public int panelStyle = 1;
	public int linesShown = 3;
	public boolean showTitle = true;
	public boolean showProgress = true;
	public boolean boldActive = false;
	public int lyricsOffsetMs = 150;
	public int hudBottomOffset = 70;

	// New features
	public boolean scatteredMode = false;
	public boolean skyHorizonMode = false;
	public boolean showCoverArt = true;
	public String skyTheme = "vanilla";

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
		if (skyTheme == null) skyTheme = "vanilla";
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
