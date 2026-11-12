package dev.spotifylyrics;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;

public final class SpotifyLyricsMod implements ClientModInitializer {
	public static final String MOD_ID = "spotifylyrics";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Config config;
	private static SpotifyAuth auth;
	private static SpotifyApi api;
	private static PlaybackTracker tracker;

	private static KeyMapping settingsKey;
	private static boolean openSettingsNextTick;
	private static boolean hintShown;
	private static int ticksInWorld;

	public static Config config() {
		return config;
	}

	public static SpotifyAuth auth() {
		return auth;
	}

	public static SpotifyApi api() {
		return api;
	}

	public static PlaybackTracker tracker() {
		return tracker;
	}

	@Override
	public void onInitializeClient() {
		config = Config.load();
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		auth = new SpotifyAuth(config, http);
		api = new SpotifyApi(auth, http);
		tracker = new PlaybackTracker(auth, api, http);
		// Starts polling immediately. Once you've logged in once, lyrics appear by themselves
		// whenever a song plays in Spotify, with no commands needed.
		tracker.start();

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "lyrics"), LyricsHud::extract);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + MOD_ID + ".settings", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (settingsKey.consumeClick()) {
				openSettings(client);
			}
			if (openSettingsNextTick) {
				openSettingsNextTick = false;
				openSettings(client);
			}
			if (client.player == null) {
				ticksInWorld = 0;
			} else if (!hintShown && ++ticksInWorld > 100 && !auth.isLoggedIn()) {
				hintShown = true;
				Component open = Component.literal("[Open settings]")
						.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("spotify settings"))
								.withUnderlined(true).withColor(ChatFormatting.GREEN));
				say(Component.literal("Connect Spotify once to get lyrics on screen: ").append(open)
						.append(Component.literal(" (or press K)")));
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("spotify")
						.executes(ctx -> {
							openSettingsNextTick = true;
							return 1;
						})
						.then(ClientCommands.literal("settings").executes(ctx -> {
							openSettingsNextTick = true;
							return 1;
						}))
						.then(ClientCommands.literal("clientid")
								.then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
									config.clientId = StringArgumentType.getString(ctx, "id").trim();
									config.save();
									say("Client ID saved. Now run /spotify login");
									return 1;
								})))
						.then(ClientCommands.literal("login").executes(ctx -> {
							startLogin();
							return 1;
						}))
						.then(ClientCommands.literal("logout").executes(ctx -> {
							auth.logout();
							say("Logged out of Spotify.");
							return 1;
						}))
						.then(ClientCommands.literal("play")
								.executes(ctx -> {
									tracker.act(api::resume);
									return 1;
								})
								.then(ClientCommands.argument("query", StringArgumentType.greedyString()).executes(ctx -> {
									String q = StringArgumentType.getString(ctx, "query");
									tracker.act(() -> api.searchAndPlay(q));
									return 1;
								})))
						.then(ClientCommands.literal("pause").executes(ctx -> {
							tracker.act(api::pause);
							return 1;
						}))
						.then(ClientCommands.literal("next").executes(ctx -> {
							tracker.act(api::next);
							return 1;
						}))
						.then(ClientCommands.literal("prev").executes(ctx -> {
							tracker.act(api::previous);
							return 1;
						}))
						.then(ClientCommands.literal("volume")
								.then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
									int v = IntegerArgumentType.getInteger(ctx, "percent");
									tracker.act(() -> api.volume(v));
									return 1;
								})))));
	}

	private static void openSettings(Minecraft client) {
		if (!(client.screen instanceof SettingsScreen)) {
			client.setScreen(new SettingsScreen(client.screen));
		}
	}

	/** Starts the browser login. Safe to call from the game thread; the wait happens on a worker thread. */
	public static void startLogin() {
		Thread t = new Thread(() -> {
			try {
				auth.login(uri -> {
					Minecraft mc = Minecraft.getInstance();
					mc.execute(() -> mc.keyboardHandler.setClipboard(uri.toString()));
					Component link = Component.literal("[Click to log in to Spotify]")
							.withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(uri))
									.withUnderlined(true).withColor(ChatFormatting.GREEN));
					say(Component.literal("Approve the login in your browser. ").append(link)
							.append(Component.literal(" (link also copied to clipboard)")));
				});
				say("Spotify connected! Lyrics will now appear automatically whenever a song plays.");
				tracker.pollSoon();
			} catch (Exception e) {
				say(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage()).withStyle(ChatFormatting.RED));
			}
		}, "spotifylyrics-login");
		t.setDaemon(true);
		t.start();
	}

	static void say(String text) {
		say(Component.literal(text));
	}

	/** Safe to call from any thread: hops to the game thread before touching the chat. */
	static void say(Component text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal("[Spotify] ").withStyle(ChatFormatting.GREEN).append(text));
			}
		});
	}
}
