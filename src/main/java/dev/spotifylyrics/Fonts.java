package dev.spotifylyrics;

import net.minecraft.resources.Identifier;

import java.util.List;

/** Fonts the player can pick from. TTF files and their definitions live in assets/spotifylyrics/font. */
public final class Fonts {
	/** fontId == null means Minecraft's normal font. */
	public record FontChoice(String id, String label, Identifier fontId) {
	}

	private static Identifier mine(String name) {
		return Identifier.fromNamespaceAndPath(SpotifyLyricsMod.MOD_ID, name);
	}

	public static final List<FontChoice> ALL = List.of(
			new FontChoice("default", "Minecraft", null),
			new FontChoice("uniform", "Minecraft Uniform", Identifier.withDefaultNamespace("uniform")),
			new FontChoice("poppins", "Poppins", mine("poppins")),
			new FontChoice("poppins_bold", "Poppins Bold", mine("poppins_bold")),
			new FontChoice("lora", "Lora (serif)", mine("lora")),
			new FontChoice("carlito", "Carlito", mine("carlito")),
			new FontChoice("dejavu_bold", "DejaVu Sans Bold", mine("dejavu_bold")),
			new FontChoice("liberation_mono", "Liberation Mono", mine("liberation_mono"))
	);

	private Fonts() {
	}

	public static FontChoice byId(String id) {
		for (FontChoice f : ALL) if (f.id().equals(id)) return f;
		return ALL.get(0);
	}

	public static FontChoice next(String id) {
		int i = ALL.indexOf(byId(id));
		return ALL.get((i + 1) % ALL.size());
	}
}
