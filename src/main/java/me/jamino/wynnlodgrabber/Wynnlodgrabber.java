package me.jamino.wynnlodgrabber;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wynntils.core.components.Models;
import com.wynntils.models.character.CharacterModel;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class Wynnlodgrabber implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("wynnlodgrabber");

    private static final int INSTALL_SETTLE_SECONDS = 5;
    /** LOD release that every install from before versions were recorded came from. */
    private static final String LEGACY_LOD_VERSION = "LOD-04-19-26";

    private static Config config;
    private static volatile LodProgress activeDownload = null;
    private static volatile LodInstallJob installJob = null;
    /** The server we were connected to when the pending package was downloaded; used to rejoin after install. */
    private static volatile ServerData pendingServer = null;
    private static boolean wynntilsLoaded          = false;
    private static boolean dhLoaded                = false;
    private static boolean voxyLoaded              = false;

    private int tickCounter = 0;
    private static final int CHECK_INTERVAL = 20;
    private static final int CHARACTER_LOAD_DELAY = 5; // checks (5 seconds) before prompting
    private int characterReadyChecks = 0;
    private boolean promptSuppressedUntilRestart = false;
    private boolean conflictShown = false;
    private boolean readyPromptQueued = false;
    private boolean pendingPromptHandled = false;
    private boolean autoInstallTried = false;
    private boolean updateCheckStarted = false;
    private boolean updatePromptShown = false;
    /** Set when the published manifest is newer than what the player has installed. */
    private static volatile String availableUpdateVersion = null;
    private boolean sessionHadWorld = false;
    private DhCompat dhCompat = null;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing WynnLODGrabber...");
        try {
            Path configDir = configDir();
            Path configPath = configDir.resolve("config.json");
            config = Config.load(configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to initialize mod directories:", e);
            return;
        }

        wynntilsLoaded = FabricLoader.getInstance().isModLoaded("wynntils");
        if (!wynntilsLoaded) {
            LOGGER.error("Wynntils mod not found! This mod requires Wynntils to function.");
            return;
        }

        dhLoaded   = FabricLoader.getInstance().isModLoaded("distanthorizons");
        voxyLoaded = FabricLoader.getInstance().isModLoaded("voxy");

        if (!dhLoaded && !voxyLoaded) {
            LOGGER.error("Neither Distant Horizons nor Voxy found! Install at least one LOD mod.");
            return;
        }

        cleanUpStaleFiles();

        if (dhLoaded) {
            dhCompat = new DhCompat(() -> {});
            dhCompat.registerEvents();
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        registerCommands();

        LOGGER.info("WynnLODGrabber initialized. DH={}, Voxy={}", dhLoaded, voxyLoaded);
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("wynnlodgrabber");
    }

    private void cleanUpStaleFiles() {
        // Leftover from the pre-1.3 downloader, which could orphan a multi-GB temp file on failure.
        LodFiles.deleteQuietly(configDir().resolve("download_temp.zip"));
        LodFiles.deleteQuietly(configDir().resolve("temp_extract"));

        // A pending install whose staged files vanished can never be installed; forget it.
        if (!config.pendingMod.isEmpty() && !hasPending()) {
            LOGGER.warn("Pending {} install has no staged files, clearing it", config.pendingMod);
            clearPending();
        }
    }

    // ---- events -----------------------------------------------------------------------------------

    private void onPlayerJoin(ClientPacketListener handler, PacketSender sender, Minecraft client) {
        sessionHadWorld = true;

        String serverIp = client.getCurrentServer() != null ? client.getCurrentServer().ip : "";
        if (!serverIp.contains("wynncraft")) return;

        tickCounter = 0;
        characterReadyChecks = 0;

        if (dhLoaded && dhCompat != null && dhCompat.isInitialized()) {
            dhCompat.configure();
        }

        // Notify if LODs were installed for a different wynncraft IP
        if (dhLoaded && config.hasDownloadedDhLods
                && !config.installedDhIp.isEmpty()
                && !serverIp.equals(config.installedDhIp)) {
            sendChat(client, "DH LODs were installed for " + config.installedDhIp
                    + ". Use /wynn_lod_force to reinstall for this server.", ChatFormatting.YELLOW);
        }
        if (voxyLoaded && config.hasDownloadedVoxyLods
                && !config.installedVoxyIp.isEmpty()
                && !serverIp.equals(config.installedVoxyIp)) {
            sendChat(client, "Voxy LODs were installed for " + config.installedVoxyIp
                    + ". Use /wynn_lod_force to reinstall for this server.", ChatFormatting.YELLOW);
        }

        checkForLodUpdate(client);
    }

    private void onClientTick(Minecraft client) {
        // Show conflict screen once if both LOD mods are installed
        if (dhLoaded && voxyLoaded && !conflictShown) {
            conflictShown = true;
            client.execute(() -> client.setScreen(new ConflictScreen()));
            return;
        }

        tryAutoInstallAtTitle(client);

        if (!wynntilsLoaded || client.player == null) return;

        if (readyPromptQueued && client.screen == null && hasPending() && !isInstallRunning()) {
            readyPromptQueued = false;
            showReadyScreen(client);
        }

        if (promptSuppressedUntilRestart) return;

        tickCounter++;
        if (tickCounter >= CHECK_INTERVAL) {
            tickCounter = 0;
            checkCharacterSelected(client);
        }
    }

    /**
     * Once the player is back on the title screen (or server list) with a fully downloaded package waiting,
     * install it. That is what "Install When I Leave" promises, and it also finishes installs that were
     * interrupted by closing the game.
     */
    private void tryAutoInstallAtTitle(Minecraft client) {
        if (autoInstallTried || config == null || client.level != null || !hasPending()) return;
        if (isDownloading() || isInstallRunning()) return;
        if (!(client.screen instanceof TitleScreen || client.screen instanceof JoinMultiplayerScreen)) return;

        autoInstallTried = true;
        // At game launch the LOD mod has no world loaded, so there is nothing to wait for.
        LodInstallJob job = newInstallJob(false, sessionHadWorld ? INSTALL_SETTLE_SECONDS : 0);
        installJob = job;
        client.setScreen(new LodInstallScreen(job));
        job.start();
    }

    private void checkCharacterSelected(Minecraft client) {
        try {
            CharacterModel character = Models.Character;
            if (!character.hasCharacter() || isDownloading() || isInstallRunning()) {
                characterReadyChecks = 0;
                return;
            }

            characterReadyChecks++;
            if (characterReadyChecks < CHARACTER_LOAD_DELAY) return;

            String serverAddress = client.getCurrentServer() != null ? client.getCurrentServer().ip : "";
            if (!serverAddress.contains("wynncraft")) return;

            // Don't stack screens on top of whatever the player has open (including our own prompt).
            if (client.screen != null) return;

            // A finished download from an earlier session is waiting: offer to install it instead of re-asking.
            if (hasPending()) {
                if (!pendingPromptHandled) {
                    pendingPromptHandled = true;
                    readyPromptQueued = true;
                }
                return;
            }

            // Already installed, but a newer LOD release was published: ask once per session.
            String updateVersion = availableUpdateVersion;
            if (updateVersion != null && !updatePromptShown && !updateVersion.equals(config.skippedLodVersion)) {
                updatePromptShown = true;
                showUpdatePrompt(client, updateVersion);
                return;
            }

            if (dhLoaded && !config.hasDownloadedDhLods && !config.hasDeclinedDh) {
                showDownloadPrompt(client, "Distant Horizons", "dh");
            } else if (voxyLoaded && !config.hasDownloadedVoxyLods && !config.hasDeclinedVoxy) {
                showDownloadPrompt(client, "Voxy", "voxy");
            }
        } catch (Exception e) {
            LOGGER.error("Error checking character selection:", e);
        }
    }

    private void showDownloadPrompt(Minecraft client, String label, String mod) {
        client.execute(() -> client.setScreen(new LodPromptScreen(
                client.screen,
                label,
                () -> onYesCommand(client, mod),
                () -> onNoCommand(client, mod),
                () -> {
                    promptSuppressedUntilRestart = true;
                    LOGGER.info("{} LOD prompt suppressed until restart", label);
                }
        )));
    }

    private void showUpdatePrompt(Minecraft client, String version) {
        String mod = dhLoaded ? "dh" : "voxy";
        String label = dhLoaded ? "Distant Horizons" : "Voxy";
        client.execute(() -> client.setScreen(new LodPromptScreen(
                client.screen,
                label,
                version,
                () -> onYesCommand(client, mod),
                () -> {
                    config.skippedLodVersion = version;
                    saveConfig();
                    sendChat(client, "Skipping " + version + ". You'll be asked again when newer LODs are published, "
                            + "or update any time with /wynn_lod_update.", ChatFormatting.YELLOW);
                },
                () -> sendChat(client, "You can update any time with /wynn_lod_update.", ChatFormatting.YELLOW)
        )));
    }

    private void updateCommand(Minecraft client) {
        if (availableUpdateVersion == null) {
            sendChat(client, "Your LODs are up to date. Use /wynn_lod_force to reinstall them anyway.",
                    ChatFormatting.YELLOW);
            return;
        }
        startDownload(client, dhLoaded ? "dh" : "voxy");
    }

    // ---- commands ---------------------------------------------------------------------------------

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("wynn_lod_yes")
                    .executes(context -> {
                        String mod = dhLoaded ? "dh" : "voxy";
                        onYesCommand(Minecraft.getInstance(), mod);
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_no")
                    .executes(context -> {
                        String mod = dhLoaded ? "dh" : "voxy";
                        onNoCommand(Minecraft.getInstance(), mod);
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_force")
                    .executes(context -> {
                        forceDownload(Minecraft.getInstance());
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_update")
                    .executes(context -> {
                        updateCommand(Minecraft.getInstance());
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_install")
                    .executes(context -> {
                        beginInstallNow(Minecraft.getInstance());
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_cancel")
                    .executes(context -> {
                        cancelOrDiscard(Minecraft.getInstance());
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_status")
                    .executes(context -> {
                        showStatus(Minecraft.getInstance());
                        return 1;
                    }));

            if (dhLoaded) {
                dispatcher.register(literal("wynn_dh_config")
                        .executes(context -> {
                            showDhConfig(Minecraft.getInstance());
                            return 1;
                        }));
            }
        });
    }

    private void showDhConfig(Minecraft client) {
        if (dhCompat == null || !dhCompat.isInitialized()) {
            sendChat(client, "Distant Horizons is not yet initialized.", ChatFormatting.RED);
            return;
        }
        sendChat(client, "Current DH Folder Mode: " + dhCompat.getFolderMode(), ChatFormatting.GREEN);
        boolean success = dhCompat.setFolderModeIpOnly();
        sendChat(client, success ? "Folder mode set to IP_ONLY" : "Failed to set folder mode (may be locked)",
                success ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private void showStatus(Minecraft client) {
        sendChat(client, "LOD Download Status:", ChatFormatting.YELLOW);
        if (dhLoaded) {
            sendChat(client, "- DH LODs installed: " + config.hasDownloadedDhLods
                    + (config.hasDownloadedDhLods ? " (" + config.installedDhIp
                    + (config.installedDhVersion.isEmpty() ? "" : ", " + config.installedDhVersion) + ")" : ""),
                    ChatFormatting.WHITE);
            sendChat(client, "- DH declined: " + config.hasDeclinedDh, ChatFormatting.WHITE);
        }
        if (voxyLoaded) {
            sendChat(client, "- Voxy LODs installed: " + config.hasDownloadedVoxyLods
                    + (config.hasDownloadedVoxyLods ? " (" + config.installedVoxyIp
                    + (config.installedVoxyVersion.isEmpty() ? "" : ", " + config.installedVoxyVersion) + ")" : ""),
                    ChatFormatting.WHITE);
            sendChat(client, "- Voxy declined: " + config.hasDeclinedVoxy, ChatFormatting.WHITE);
        }

        LodProgress download = activeDownload;
        if (download != null && !download.isTerminal()) {
            sendChat(client, "- Downloading: " + download.phase() + " "
                    + Math.round(download.fraction() * 100) + "%", ChatFormatting.WHITE);
        } else {
            sendChat(client, "- Currently downloading: false", ChatFormatting.WHITE);
        }

        if (availableUpdateVersion != null) {
            sendClickable(client, "- Update available (" + availableUpdateVersion + "). Click here to update.",
                    "/wynn_lod_update", ChatFormatting.GREEN);
        }

        if (hasPending()) {
            sendClickable(client, "- Downloaded and waiting to install. Click here to install now.",
                    "/wynn_lod_install", ChatFormatting.GREEN);
        }

        if (dhLoaded && dhCompat != null && dhCompat.isInitialized()) {
            sendChat(client, "- DH Folder Mode: " + dhCompat.getFolderMode(), ChatFormatting.WHITE);
        }

        boolean needsDownload = (dhLoaded && !config.hasDownloadedDhLods)
                || (voxyLoaded && !config.hasDownloadedVoxyLods);
        if (needsDownload && !isDownloading() && !hasPending()) {
            sendClickable(client, "Click here to download LODs", "/wynn_lod_yes", ChatFormatting.GREEN);
        }
    }

    public void onYesCommand(Minecraft client, String mod) {
        startDownload(client, "dh".equals(mod) ? "dh" : "voxy");
    }

    public void onNoCommand(Minecraft client, String mod) {
        if ("dh".equals(mod)) {
            config.hasDeclinedDh = true;
        } else {
            config.hasDeclinedVoxy = true;
        }
        saveConfig();
        sendChat(client, "You can always download the LODs later with /wynn_lod_yes", ChatFormatting.YELLOW);
    }

    public void forceDownload(Minecraft client) {
        if (isDownloading() || isInstallRunning()) {
            sendChat(client, "Wait for the current download/install to finish (or /wynn_lod_cancel) first.",
                    ChatFormatting.RED);
            return;
        }
        config.hasDownloadedDhLods   = false;
        config.hasDownloadedVoxyLods = false;
        config.hasDeclinedDh         = false;
        config.hasDeclinedVoxy       = false;
        discardPending();
        saveConfig();
        startDownload(client, dhLoaded ? "dh" : "voxy");
    }

    // ---- download ---------------------------------------------------------------------------------

    private static boolean isDownloading() {
        LodProgress download = activeDownload;
        return download != null && !download.isTerminal();
    }

    private static boolean isInstallRunning() {
        LodInstallJob job = installJob;
        return job != null && job.isRunning();
    }

    private void startDownload(Minecraft client, String mod) {
        if (isDownloading()) {
            sendChat(client, "A download is already in progress! (/wynn_lod_cancel to stop it)", ChatFormatting.RED);
            return;
        }
        if (isInstallRunning()) {
            sendChat(client, "An install is in progress, please wait.", ChatFormatting.RED);
            return;
        }
        if (hasPending()) {
            sendChat(client, "The LODs are already downloaded and just need to be installed.", ChatFormatting.YELLOW);
            readyPromptQueued = true;
            return;
        }

        ServerData server = client.getCurrentServer();
        if (server == null) {
            sendChat(client, "Join Wynncraft first, then run this again.", ChatFormatting.RED);
            return;
        }
        final String serverIp = server.ip;

        String label = "dh".equals(mod) ? "Distant Horizons" : "Voxy";
        LodProgress progress = new LodProgress(mod, label);
        activeDownload = progress;
        client.getToastManager().addToast(new LodDownloadToast(progress));
        sendChat(client, "Downloading " + label + " LODs in the background - keep playing! "
                + "Progress is shown in the corner.", ChatFormatting.YELLOW);

        Thread downloadThread = new Thread(() -> runDownload(client, mod, serverIp, server, progress),
                "WynnLOD-Downloader-" + mod);
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private void runDownload(Minecraft client, String mod, String serverIp, ServerData server, LodProgress progress) {
        try {
            progress.detail = "Checking for the latest LODs...";
            LodManifest manifest = LodManifest.fetch();
            if (manifest == null) manifest = LodManifest.current();

            LodManifest.Package pkg = manifest.get(mod);
            if (pkg == null) {
                throw new IOException("No " + progress.label + " LODs are published yet.");
            }

            Path staging = LodDownloader.download(pkg, mod, configDir(), progress);
            LOGGER.info("Downloaded and staged {} LODs at {}", mod, staging);

            config.pendingMod = mod;
            config.pendingIp = serverIp;
            config.pendingVersion = manifest.version;
            config.save();
            pendingServer = server;
            pendingPromptHandled = true;

            progress.setPhase(LodProgress.Phase.READY);
            client.execute(() -> offerInstall(client));
        } catch (CancellationException e) {
            LOGGER.info("LOD download cancelled");
            progress.setPhase(LodProgress.Phase.CANCELLED);
            sendChat(client, "LOD download cancelled. Run /wynn_lod_yes any time to resume where it left off.",
                    ChatFormatting.YELLOW);
        } catch (Exception e) {
            LOGGER.error("LOD download failed:", e);
            progress.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            progress.setPhase(LodProgress.Phase.FAILED);
            sendClickable(client, "LOD download failed: " + progress.error
                    + " - click here to retry (it resumes where it stopped).", "/wynn_lod_yes", ChatFormatting.RED);
        }
    }

    // ---- install ----------------------------------------------------------------------------------

    private static boolean hasPending() {
        if (config == null || config.pendingMod.isEmpty()) return false;
        return Files.isDirectory(LodDownloader.stagingDir(configDir(), config.pendingMod));
    }

    private void offerInstall(Minecraft client) {
        sendClickable(client, "LODs downloaded! Click here to install them now.", "/wynn_lod_install",
                ChatFormatting.GREEN);
        readyPromptQueued = true;
    }

    private void showReadyScreen(Minecraft client) {
        String label = "dh".equals(config.pendingMod) ? "Distant Horizons" : "Voxy";
        client.setScreen(new InstallReadyScreen(
                label,
                () -> beginInstallNow(client),
                () -> sendChat(client, "OK! The LODs will be installed when you leave the server. "
                        + "You can also run /wynn_lod_install.", ChatFormatting.YELLOW)));
    }

    private LodInstallJob newInstallJob(boolean autoRejoin, int settleSeconds) {
        return new LodInstallJob(
                config.pendingMod,
                config.pendingIp,
                config.pendingVersion,
                LodDownloader.stagingDir(configDir(), config.pendingMod),
                autoRejoin,
                settleSeconds,
                pendingServer);
    }

    private void beginInstallNow(Minecraft client) {
        if (!hasPending()) {
            sendChat(client, "There are no downloaded LODs waiting to be installed.", ChatFormatting.RED);
            return;
        }
        if (isInstallRunning()) return;

        readyPromptQueued = false;
        autoInstallTried = true; // a failed install must not silently re-run when the player reaches the title screen
        boolean inWorld = client.level != null;
        LodInstallJob job = newInstallJob(true, inWorld ? INSTALL_SETTLE_SECONDS : 0);
        installJob = job;

        LodInstallScreen screen = new LodInstallScreen(job);
        if (inWorld) {
            client.disconnect(screen, false);
        } else {
            client.setScreen(screen);
        }
        job.start();
    }

    /** Called from the install thread once the files are in place. */
    static void onInstallCompleted(LodInstallJob job) throws IOException {
        if ("dh".equals(job.mod)) {
            config.hasDownloadedDhLods = true;
            config.hasDeclinedDh = false;
            config.installedDhIp = job.serverIp;
            config.installedDhVersion = job.version;
        } else {
            config.hasDownloadedVoxyLods = true;
            config.hasDeclinedVoxy = false;
            config.installedVoxyIp = job.serverIp;
            config.installedVoxyVersion = job.version;
        }
        config.pendingMod = "";
        config.pendingIp = "";
        config.pendingVersion = "";
        pendingServer = null;
        availableUpdateVersion = null;
        config.save();
    }

    private void cancelOrDiscard(Minecraft client) {
        LodProgress download = activeDownload;
        if (download != null && !download.isTerminal()) {
            download.cancel();
            return;
        }
        if (isInstallRunning()) {
            sendChat(client, "The install is already running and can't be cancelled.", ChatFormatting.RED);
            return;
        }
        if (hasPending()) {
            discardPending();
            sendChat(client, "Discarded the downloaded LODs.", ChatFormatting.YELLOW);
            return;
        }
        sendChat(client, "Nothing to cancel.", ChatFormatting.YELLOW);
    }

    private static void discardPending() {
        String mod = config.pendingMod;
        clearPending();
        if (!mod.isEmpty()) {
            Path staging = LodDownloader.stagingDir(configDir(), mod);
            Thread cleaner = new Thread(() -> LodFiles.deleteQuietly(staging), "WynnLOD-Cleanup");
            cleaner.setDaemon(true);
            cleaner.start();
        }
    }

    private static void clearPending() {
        config.pendingMod = "";
        config.pendingIp = "";
        config.pendingVersion = "";
        pendingServer = null;
        saveConfig();
    }

    // ---- updates ----------------------------------------------------------------------------------

    /**
     * Once per session, compare the published LOD release with what is installed. If it differs,
     * {@link #availableUpdateVersion} is set and {@link #checkCharacterSelected} prompts the player.
     */
    private void checkForLodUpdate(Minecraft client) {
        if (updateCheckStarted) return;
        updateCheckStarted = true;

        Thread checker = new Thread(() -> {
            LodManifest manifest = LodManifest.fetch();
            if (manifest == null) return;

            String mod = dhLoaded ? "dh" : "voxy";
            boolean installed = dhLoaded ? config.hasDownloadedDhLods : config.hasDownloadedVoxyLods;
            String installedVersion = dhLoaded ? config.installedDhVersion : config.installedVoxyVersion;
            // Installs from before versions were recorded all came from the release that existed then.
            if (installedVersion.isEmpty()) installedVersion = LEGACY_LOD_VERSION;

            if (!installed || manifest.get(mod) == null || installedVersion.equals(manifest.version)) return;
            if (hasPending() || isDownloading()) return;

            LOGGER.info("LOD update available: installed {}, published {}", installedVersion, manifest.version);
            availableUpdateVersion = manifest.version;
        }, "WynnLOD-UpdateCheck");
        checker.setDaemon(true);
        checker.start();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static void saveConfig() {
        try {
            config.save();
        } catch (IOException e) {
            LOGGER.error("Failed to save config:", e);
        }
    }

    /** Safe to call from any thread. */
    private static void sendChat(Minecraft client, String message, ChatFormatting color) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal(message).withStyle(color), false);
            }
        });
    }

    private static void sendClickable(Minecraft client, String message, String command, ChatFormatting color) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal(message)
                        .withStyle(color)
                        .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))), false);
            }
        });
    }
}
