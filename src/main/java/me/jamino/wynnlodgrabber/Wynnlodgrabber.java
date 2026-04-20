package me.jamino.wynnlodgrabber;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wynntils.core.components.Models;
import com.wynntils.models.character.CharacterModel;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class Wynnlodgrabber implements ModInitializer {
    private static final String DH_DOWNLOAD_URL   = "https://github.com/DrBiznes/WynnLODGrabber/releases/download/LOD-11-05-24/wynnlods.zip";
    private static final String VOXY_DOWNLOAD_URL = "https://github.com/DrBiznes/WynnLODGrabber/releases/download/LOD-04-19-26/frumavoxylods.zip";
    private static final String DH_DATA_DIR       = "Distant_Horizons_server_data";
    private static final String VOXY_DATA_DIR     = ".voxy/saves";

    public static final Logger LOGGER = LoggerFactory.getLogger("wynnlodgrabber");

    private static Config config;
    private static boolean isCurrentlyDownloading  = false;
    private static boolean wynntilsLoaded          = false;
    private static boolean dhLoaded                = false;
    private static boolean voxyLoaded              = false;

    private int tickCounter = 0;
    private static final int CHECK_INTERVAL = 20;
    private boolean promptSuppressedUntilRestart = false;
    private boolean conflictShown = false;
    private DhCompat dhCompat = null;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing WynnLODGrabber...");
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve("wynnlodgrabber");
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

        if (dhLoaded) {
            dhCompat = new DhCompat(() -> {});
            dhCompat.registerEvents();
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        registerCommands();

        LOGGER.info("WynnLODGrabber initialized. DH={}, Voxy={}", dhLoaded, voxyLoaded);
    }

    private void onPlayerJoin(ClientPacketListener handler, PacketSender sender, Minecraft client) {
        String serverIp = client.getCurrentServer() != null ? client.getCurrentServer().ip : "";
        if (!serverIp.contains("wynncraft")) return;

        tickCounter = 0;

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
    }

    private void onClientTick(Minecraft client) {
        // Show conflict screen once if both LOD mods are installed
        if (dhLoaded && voxyLoaded && !conflictShown) {
            conflictShown = true;
            client.execute(() -> client.setScreen(new ConflictScreen()));
            return;
        }

        if (!wynntilsLoaded || client.player == null || promptSuppressedUntilRestart) return;

        tickCounter++;
        if (tickCounter >= CHECK_INTERVAL) {
            tickCounter = 0;
            checkCharacterSelected(client);
        }
    }

    private void checkCharacterSelected(Minecraft client) {
        try {
            CharacterModel character = Models.Character;
            if (!character.hasCharacter() || isCurrentlyDownloading) return;

            String serverAddress = client.getCurrentServer() != null ? client.getCurrentServer().ip : "";
            if (!serverAddress.contains("wynncraft")) return;

            if (dhLoaded && !config.hasDownloadedDhLods && !config.hasDeclinedDh) {
                client.execute(() -> client.setScreen(new LodPromptScreen(
                        client.screen,
                        "Distant Horizons",
                        () -> onYesCommand(client, "dh"),
                        () -> onNoCommand(client, "dh"),
                        () -> {
                            promptSuppressedUntilRestart = true;
                            LOGGER.info("DH LOD prompt suppressed until restart");
                        }
                )));
            } else if (voxyLoaded && !config.hasDownloadedVoxyLods && !config.hasDeclinedVoxy) {
                client.execute(() -> client.setScreen(new LodPromptScreen(
                        client.screen,
                        "Voxy",
                        () -> onYesCommand(client, "voxy"),
                        () -> onNoCommand(client, "voxy"),
                        () -> {
                            promptSuppressedUntilRestart = true;
                            LOGGER.info("Voxy LOD prompt suppressed until restart");
                        }
                )));
            }
        } catch (Exception e) {
            LOGGER.error("Error checking character selection:", e);
        }
    }

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
            sendChat(client, "- DH LODs downloaded: " + config.hasDownloadedDhLods
                    + (config.hasDownloadedDhLods ? " (" + config.installedDhIp + ")" : ""), ChatFormatting.WHITE);
            sendChat(client, "- DH declined: " + config.hasDeclinedDh, ChatFormatting.WHITE);
        }
        if (voxyLoaded) {
            sendChat(client, "- Voxy LODs downloaded: " + config.hasDownloadedVoxyLods
                    + (config.hasDownloadedVoxyLods ? " (" + config.installedVoxyIp + ")" : ""), ChatFormatting.WHITE);
            sendChat(client, "- Voxy declined: " + config.hasDeclinedVoxy, ChatFormatting.WHITE);
        }
        sendChat(client, "- Currently downloading: " + isCurrentlyDownloading, ChatFormatting.WHITE);

        if (dhLoaded && dhCompat != null && dhCompat.isInitialized()) {
            sendChat(client, "- DH Folder Mode: " + dhCompat.getFolderMode(), ChatFormatting.WHITE);
        }

        boolean needsDownload = (dhLoaded && !config.hasDownloadedDhLods)
                || (voxyLoaded && !config.hasDownloadedVoxyLods);
        if (needsDownload && !isCurrentlyDownloading) {
            client.player.displayClientMessage(Component.literal("Click here to download LODs")
                    .withStyle(ChatFormatting.GREEN)
                    .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/wynn_lod_yes"))), false);
        }
    }

    public void onYesCommand(Minecraft client, String mod) {
        if ("dh".equals(mod)) {
            downloadAndInstallLods(client, "dh");
        } else {
            downloadAndInstallLods(client, "voxy");
        }
    }

    public void onNoCommand(Minecraft client, String mod) {
        if ("dh".equals(mod)) {
            config.hasDeclinedDh = true;
        } else {
            config.hasDeclinedVoxy = true;
        }
        try {
            config.save();
        } catch (IOException e) {
            LOGGER.error("Failed to save config:", e);
        }
        sendChat(client, "You can always download the LODs later with /wynn_lod_yes", ChatFormatting.YELLOW);
    }

    public void forceDownload(Minecraft client) {
        config.hasDownloadedDhLods   = false;
        config.hasDownloadedVoxyLods = false;
        config.hasDeclinedDh         = false;
        config.hasDeclinedVoxy       = false;
        try {
            config.save();
        } catch (IOException e) {
            LOGGER.error("Failed to save config during force download:", e);
        }
        String mod = dhLoaded ? "dh" : "voxy";
        downloadAndInstallLods(client, mod);
    }

    private void downloadAndInstallLods(Minecraft client, String mod) {
        if (isCurrentlyDownloading) {
            sendChat(client, "Download already in progress!", ChatFormatting.RED);
            return;
        }

        String downloadUrl = "dh".equals(mod) ? DH_DOWNLOAD_URL : VOXY_DOWNLOAD_URL;
        if (downloadUrl.isEmpty()) {
            sendChat(client, "Download URL for " + mod.toUpperCase() + " LODs is not configured yet.", ChatFormatting.RED);
            return;
        }

        Thread downloadThread = new Thread(() -> {
            isCurrentlyDownloading = true;
            Path tempFile = null;
            try {
                Path configDir = FabricLoader.getInstance().getConfigDir().resolve("wynnlodgrabber");
                Files.createDirectories(configDir);

                sendProgressMessage(client, "Starting download, don't leave the game until complete...", ChatFormatting.YELLOW);

                URL url = new URL(downloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "WynnLODGrabber Mod");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Server returned response code: " + responseCode);
                }

                int fileSize = connection.getContentLength();
                tempFile = configDir.resolve("download_temp.zip");
                Files.deleteIfExists(tempFile);

                try (InputStream in = connection.getInputStream();
                     OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytesRead = 0;
                    long lastProgressUpdate = 0;
                    long startTime = System.currentTimeMillis();

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;

                        if (fileSize > 0) {
                            int currentProgress = (int) ((totalBytesRead * 100) / fileSize);
                            if (currentProgress >= lastProgressUpdate + 10) {
                                lastProgressUpdate = currentProgress;
                                long elapsed = System.currentTimeMillis() - startTime;
                                double speedMBps = (totalBytesRead / 1024.0 / 1024.0) / (elapsed / 1000.0);
                                sendProgressMessage(client,
                                        String.format("Download progress: %d%% (%.1f MB/s)", currentProgress, speedMBps),
                                        ChatFormatting.AQUA);
                            }
                        }
                    }
                }

                LOGGER.info("Download complete, size: {} bytes", Files.size(tempFile));
                sendProgressMessage(client, "Download complete! Disconnecting in 5 seconds to install LODs...", ChatFormatting.YELLOW);

                installLods(client, tempFile, mod);

            } catch (IOException e) {
                LOGGER.error("Download failed:", e);
                sendProgressMessage(client, "Error downloading LODs: " + e.getMessage(), ChatFormatting.RED);
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                }
                isCurrentlyDownloading = false;
            }
        });

        downloadThread.setName("WynnLOD-Downloader-" + mod);
        downloadThread.start();
    }

    private void installLods(Minecraft client, Path tempFile, String mod) {
        client.execute(() -> {
            try {
                Thread.sleep(5000);

                String serverIp = client.getCurrentServer() != null ? client.getCurrentServer().ip : "";

                Path targetDir;
                if ("dh".equals(mod)) {
                    String dhFolder = serverIp.replace(".", "%2E");
                    targetDir = FabricLoader.getInstance().getGameDir()
                            .resolve(DH_DATA_DIR).resolve(dhFolder);
                } else {
                    targetDir = FabricLoader.getInstance().getGameDir()
                            .resolve(VOXY_DATA_DIR).resolve(serverIp);
                }

                Files.createDirectories(targetDir);

                Minecraft.getInstance().disconnect(new DisconnectedScreen(
                        new TitleScreen(),
                        Component.literal("Disconnected"),
                        Component.literal("Installing LODs...").withStyle(ChatFormatting.GOLD)
                ), false);

                Thread.sleep(2000);

                LOGGER.info("Extracting LODs into: {}", targetDir);
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempFile))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        Path outputPath = targetDir.resolve(entry.getName());
                        if (entry.isDirectory()) {
                            Files.createDirectories(outputPath);
                        } else {
                            Files.createDirectories(outputPath.getParent());
                            Files.copy(zis, outputPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        zis.closeEntry();
                    }
                }

                Files.deleteIfExists(tempFile);

                if ("dh".equals(mod)) {
                    config.hasDownloadedDhLods = true;
                    config.installedDhIp = serverIp;
                } else {
                    config.hasDownloadedVoxyLods = true;
                    config.installedVoxyIp = serverIp;
                }
                config.save();

                LOGGER.info("LOD installation complete for {} on {}", mod, serverIp);

            } catch (Exception e) {
                LOGGER.error("Error during LOD installation:", e);
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            } finally {
                isCurrentlyDownloading = false;
            }
        });
    }

    private void sendChat(Minecraft client, String message, ChatFormatting color) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(message).withStyle(color), false);
        }
    }

    private void sendProgressMessage(Minecraft client, String message, ChatFormatting color) {
        if (client.player != null) {
            client.execute(() -> client.player.displayClientMessage(
                    Component.literal(message).withStyle(color), false));
        }
    }
}
