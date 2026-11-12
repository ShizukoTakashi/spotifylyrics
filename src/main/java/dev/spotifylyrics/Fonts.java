package dev.spotifylyrics;

import net.minecraft.resources.Identifier;

import java.util.List;

public final class Fonts {
	public record FontChoice(String id, String label, Identifier fontId, String transform) {
		public FontChoice(String id, String label, Identifier fontId) {
			this(id, label, fontId, "none");
		}
	}

	private static Identifier mine(String name) {
		return Identifier.fromNamespaceAndPath(SpotifyLyricsMod.MOD_ID, name);
	}

	public static final List<FontChoice> ALL = List.of(
			new FontChoice("default", "Minecraft", null),
			new FontChoice("cursive", "𝒞𝓊𝓇𝓈𝒾𝓋𝑒 𝒮𝒸𝓇𝒾𝓅𝓉", null, "cursive"),
			new FontChoice("calligraphy", "𝓑𝓸𝓵𝓭 𝓒𝓪𝓵𝓵𝓲𝓰𝓻𝓪𝓹𝓱𝔂", null, "calligraphy"),
			new FontChoice("gothic", "𝔊𝔬𝔱𝔥𝔦𝔠 𝔒𝔩𝔡 𝔈𝔫𝔤𝔩𝔦𝔰𝔥", null, "gothic"),
			new FontChoice("bold_italic", "𝘽𝙤𝙡𝙙 𝙄𝙩𝙖𝙡𝙞𝙘", null, "bold_italic"),
			new FontChoice("small_caps", "ꜱᴍᴀʟʟ ᴄᴀᴘꜱ", null, "small_caps"),
			new FontChoice("typewriter", "𝚃𝚢𝚙𝚎𝚠𝚛𝚒𝚝𝚎𝚛 𝙼𝚘𝚗𝚘", null, "mono"),
			new FontChoice("uniform", "Minecraft Uniform", Identifier.withDefaultNamespace("uniform")),
			new FontChoice("poppins", "Poppins", mine("poppins")),
			new FontChoice("poppins_bold", "Poppins Bold", mine("poppins_bold")),
			new FontChoice("lora", "Lora (serif)", mine("lora")),
			new FontChoice("carlito", "Carlito", mine("carlito")),
			new FontChoice("dejavu_bold", "DejaVu Sans Bold", mine("dejavu_bold")),
			new FontChoice("liberation_mono", "Liberation Mono", mine("liberation_mono"))
	);

	private Fonts() {}

	public static FontChoice byId(String id) {
		for (FontChoice f : ALL) if (f.id().equals(id)) return f;
		return ALL.get(0);
	}

	public static FontChoice next(String id) {
		int i = ALL.indexOf(byId(id));
		return ALL.get((i + 1) % ALL.size());
	}

	public static String applyStyle(String text, String transform) {
		if (text == null || transform == null || "none".equals(transform)) return text;
		StringBuilder sb = new StringBuilder(text.length() * 2);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			sb.append(mapChar(c, transform));
		}
		return sb.toString();
	}

	private static String mapChar(char c, String mode) {
		if ("small_caps".equals(mode)) {
			String normal = "abcdefghijklmnopqrstuvwxyz";
			String small = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
			int idx = normal.indexOf(Character.toLowerCase(c));
			return idx >= 0 ? String.valueOf(small.charAt(idx)) : String.valueOf(c);
		}
		int upperBase = switch (mode) {
			case "cursive" -> 0x1D49C;
			case "calligraphy" -> 0x1D4D0;
			case "gothic" -> 0x1D504;
			case "bold_italic" -> 0x1D63C;
			case "mono" -> 0x1D670;
			default -> -1;
		};
		int lowerBase = switch (mode) {
			case "cursive" -> 0x1D4B6;
			case "calligraphy" -> 0x1D4EA;
			case "gothic" -> 0x1D51E;
			case "bold_italic" -> 0x1D656;
			case "mono" -> 0x1D68A;
			default -> -1;
		};
		if (upperBase < 0) return String.valueOf(c);
		if (c >= 'A' && c <= 'Z') {
			if ("cursive".equals(mode)) {
				switch (c) {
					case 'B': return "\u212C"; case 'E': return "\u2130"; case 'F': return "\u2131";
					case 'H': return "\u210B"; case 'I': return "\u2110"; case 'L': return "\u2112";
					case 'M': return "\u2133"; case 'R': return "\u211B";
				}
			}
			if ("gothic".equals(mode)) {
				switch (c) {
					case 'C': return "\u212D"; case 'H': return "\u210C"; case 'I': return "\u2111";
					case 'R': return "\u211C"; case 'Z': return "\u2128";
				}
			}
			return new String(Character.toChars(upperBase + (c - 'A')));
		}
		if (c >= 'a' && c <= 'z') {
			if ("cursive".equals(mode)) {
				switch (c) {
					case 'e': return "\u212F"; case 'g': return "\u210A"; case 'o': return "\u2134";
				}
			}
			return new String(Character.toChars(lowerBase + (c - 'a')));
		}
		return String.valueOf(c);
	}
}
