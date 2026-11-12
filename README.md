# Lyrics HUD for Spotify (Fabric, Minecraft Java 26.3)

Play a song in Spotify and its **synced lyrics appear on your Minecraft screen automatically**.
Pick fonts and colours, and pause / skip / search songs from an in-game settings screen (press **K**).

## How it works
- **Automatic**: after you connect Spotify once, the mod starts with the game. Whenever a song plays,
  the lyrics show up. No commands needed.
- **Control**: it remote-controls the Spotify app on your PC/phone through Spotify's Web API (it doesn't stream audio
  itself, so keep Spotify open). **Playback control needs Spotify Premium.** Lyrics display works without pressing anything.
- **Lyrics** come from [LRCLIB](https://lrclib.net), a free community database. Obscure songs may have none.
- Client-side only: works in singleplayer and on any server. Requires Fabric API.

## One-time Spotify setup (about 2 minutes)
1. https://developer.spotify.com/dashboard > **Create app**. Tick **Web API**.
2. Redirect URI: `http://127.0.0.1:8888/callback` (exactly, with the IP, not `localhost`).
3. Copy the **Client ID**. In Development Mode, add your own Spotify email under *User Management*.
4. In Minecraft press **K** > **Account** tab > paste the Client ID > **Log in** > approve in your browser.

## Settings screen (K)
- **Player**: now playing + progress, prev / play-pause / next, -10s / +10s, volume, search and play.
- **Appearance**: 8 fonts, colour swatches + hex for the current and other lines, text size, height,
  timing offset, 1/3/5 lines, panel style, bold, title and progress bar toggles.
- **Account**: Client ID, log in / out.

Commands still exist if you like them: `/spotify`, `/spotify play <song>`, `pause`, `next`, `prev`, `volume <0-100>`.
Settings are saved in `config/spotifylyrics.json`. The login token is in `config/spotifylyrics-tokens.json`: treat it like a password.

## Building (needs JDK 25)
1. Fill in `fabric_api_version` in `gradle.properties` (and check loader/loom) from https://fabricmc.net/develop/ with 26.3 selected.
2. `gradle wrapper` once (Gradle 9+), then `./gradlew build`. The jar is `build/libs/spotifylyrics-0.1.0.jar`
   (not the `-sources` one).
   Or push to GitHub: the included Actions workflow builds the jar for you (download it from the run's artifacts).

## Publishing on Modrinth
1. Create a project at modrinth.com (type: Mod, client-side only, licence MIT, loader Fabric, game version 26.3).
2. Upload `spotifylyrics-0.1.0.jar` as a version, with Fabric API as a required dependency.
3. Use `assets/spotifylyrics/icon.png` as the project icon, and this README as the description.
4. Edit `authors` and `contact` in `fabric.mod.json` first. Spotify is a trademark of Spotify AB: this mod is not affiliated with
   or endorsed by Spotify, which is why it's called "Lyrics HUD".

## Credits and licences
Mod code: MIT (see `LICENSE`). Bundled fonts: Poppins, Lora, Carlito, Liberation Mono (SIL OFL 1.1) and DejaVu Sans Bold (free licence).
Details in `FONT_LICENSES.md`.
