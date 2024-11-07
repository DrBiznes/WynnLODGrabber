package me.jamino.wynnlodgrabber;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Wynntils imports
import com.wynntils.core.components.Models;
import com.wynntils.models.character.CharacterModel;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class Wynnlodgrabber implements ModInitializer {
    private static final String GITHUB_RELEASE_URL = "https://github.com/DrBiznes/WynnLODGrabber/releases/download/LOD-11-05-24/wynnlods.zip";
    private static final String DH_FOLDER = "Distant_Horizons_server_data";
    public static final Logger LOGGER = LoggerFactory.getLogger("wynnlodgrabber");

    private static Config config;
    private static boolean isCurrentlyDownloading = false;
    private static boolean wynntilsLoaded = false;
    private int tickCounter = 0;
    private static final int CHECK_INTERVAL = 20; // Check every second (20 ticks)

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing WynnLODGrabber...");
        try {
            // Create base config directory path
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve("wynnlodgrabber");
            Path configPath = configDir.resolve("config.json");

            LOGGER.info("Loading config from: " + configPath);
            config = Config.load(configPath);
            LOGGER.info("Config loaded successfully. Download status: " + config.hasDownloadedLODs);

            // Check if Wynntils is loaded
            wynntilsLoaded = FabricLoader.getInstance().isModLoaded("wynntils");
            if (!wynntilsLoaded) {
                LOGGER.error("Wynntils mod not found! This mod requires Wynntils to function properly.");
                return;
            }

            // Create necessary subdirectories
            Files.createDirectories(configDir.resolve("temp_extract"));

            LOGGER.info("Created necessary directories in: " + configDir);
        } catch (IOException e) {
            LOGGER.error("Failed to initialize mod directories:", e);
            return;
        }

        // Register tick event for character check
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Register connection event
        ClientPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        LOGGER.info("Registered server join event handler");

        registerCommands();
        LOGGER.info("Registered mod commands");
    }

    private void onClientTick(MinecraftClient client) {
        if (!wynntilsLoaded || client.player == null || config.hasDownloadedLODs || config.hasDeclined) {
            return;
        }

        tickCounter++;
        if (tickCounter >= CHECK_INTERVAL) {
            tickCounter = 0;
            checkCharacterSelected(client);
        }
    }

    private void checkCharacterSelected(MinecraftClient client) {
        try {
            CharacterModel character = Models.Character;
            if (character.hasCharacter() && !isCurrentlyDownloading) {
                // Only show prompt if on Wynncraft server
                String serverAddress = client.getCurrentServerEntry() != null
                        ? client.getCurrentServerEntry().address
                        : "";
                if (serverAddress.contains("wynncraft")) {
                    client.execute(() -> {
                        client.setScreen(new LodPromptScreen(
                                client.currentScreen,
                                () -> onYesCommand(client),
                                () -> onNoCommand(client),
                                () -> client.setScreen(null) // Not Right Now action
                        ));
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error checking character selection:", e);
        }
    }

    private void onPlayerJoin(ClientPlayNetworkHandler handler, PacketSender sender, MinecraftClient client) {
        if (!handler.getConnection().getAddress().toString().contains("wynncraft")) {
            return;
        }

        // Reset tick counter on join
        tickCounter = 0;
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("wynn_lod_yes")
                    .executes(context -> {
                        LOGGER.info("Executing wynn_lod_yes command");
                        onYesCommand(MinecraftClient.getInstance());
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_no")
                    .executes(context -> {
                        LOGGER.info("Executing wynn_lod_no command");
                        onNoCommand(MinecraftClient.getInstance());
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_force")
                    .executes(context -> {
                        LOGGER.info("Executing wynn_lod_force command");
                        forceDownload(MinecraftClient.getInstance());
                        return 1;
                    }));

            dispatcher.register(literal("wynn_lod_status")
                    .executes(context -> {
                        LOGGER.info("Executing wynn_lod_status command");
                        showStatus(MinecraftClient.getInstance());
                        return 1;
                    }));
        });
    }

    private void showStatus(MinecraftClient client) {
        client.player.sendMessage(Text.literal("LOD Download Status:")
                .formatted(Formatting.YELLOW));
        client.player.sendMessage(Text.literal("- Has declined: " + config.hasDeclined)
                .formatted(Formatting.WHITE));
        client.player.sendMessage(Text.literal("- Has downloaded LODs: " + config.hasDownloadedLODs)
                .formatted(Formatting.WHITE));
        client.player.sendMessage(Text.literal("- Currently downloading: " + isCurrentlyDownloading)
                .formatted(Formatting.WHITE));

        if (!config.hasDownloadedLODs && !isCurrentlyDownloading) {
            client.player.sendMessage(Text.literal("Click here to download LODs")
                    .formatted(Formatting.GREEN)
                    .styled(style -> style.withClickEvent(
                            new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/wynn_lod_yes"))));
        }
    }

    private void deleteDirectoryRecursively(Path path) {
        LOGGER.info("Starting recursive deletion of directory: " + path);

        if (!Files.exists(path)) {
            LOGGER.info("Directory doesn't exist, nothing to delete: " + path);
            return;
        }

        try {
            Files.walk(path)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(file -> {
                        try {
                            LOGGER.debug("Attempting to delete: " + file);
                            Files.deleteIfExists(file);
                            LOGGER.debug("Successfully deleted: " + file);
                        } catch (IOException e) {
                            LOGGER.error("Failed to delete file: " + file, e);

                            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                                try {
                                    LOGGER.info("Attempting force delete on Windows for: " + file);
                                    System.gc();
                                    Thread.sleep(100);
                                    Files.deleteIfExists(file);
                                    LOGGER.info("Force delete successful for: " + file);
                                } catch (IOException | InterruptedException ex) {
                                    LOGGER.error("Force delete also failed for: " + file, ex);
                                    if (ex instanceof InterruptedException) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }
                        }
                    });

            LOGGER.info("Completed recursive deletion of directory: " + path);
        } catch (IOException e) {
            LOGGER.error("Failed to walk directory tree for deletion: " + path, e);
            throw new RuntimeException("Failed to delete directory: " + path, e);
        }
    }

    private void sendProgressMessage(MinecraftClient client, String message, Formatting color) {
        if (client.player != null) {
            client.execute(() -> client.player.sendMessage(
                    Text.literal(message).formatted(color)
            ));
        }
    }

    private void disconnectAndInstall(MinecraftClient client, Path dhFolder, Path tempFile) {
        LOGGER.info("Preparing installation process...");
        Path modConfigDir = FabricLoader.getInstance().getConfigDir().resolve("wynnlodgrabber");
        Path tempExtractDir = modConfigDir.resolve("temp_extract");

        try {
            if (Files.exists(tempExtractDir)) {
                LOGGER.info("Cleaning up existing temp directory...");
                deleteDirectoryRecursively(tempExtractDir);
            }

            Files.createDirectories(tempExtractDir);
            LOGGER.info("Created temporary extraction directory: " + tempExtractDir);

            LOGGER.info("Pre-extracting files to temporary directory...");
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path outputPath = tempExtractDir.resolve(entry.getName());
                    Files.createDirectories(outputPath.getParent());

                    if (!entry.isDirectory()) {
                        LOGGER.info("Extracting: " + entry.getName() + " to " + outputPath);
                        Files.copy(zis, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zis.closeEntry();
                }
            }

            client.execute(() -> {
                try {
                    Thread.sleep(5000);

                    MinecraftClient.getInstance().disconnect(new DisconnectedScreen(
                            new TitleScreen(),
                            Text.literal("Disconnected"),
                            Text.literal("Finishing LOD Installation...").formatted(Formatting.GOLD)
                    ));

                    Thread.sleep(2000);

                    cleanupExistingFiles(dhFolder);
                    Files.createDirectories(dhFolder);

                    LOGGER.info("Moving files from temporary directory to final location...");
                    Files.walk(tempExtractDir)
                            .filter(Files::isRegularFile)
                            .forEach(source -> {
                                try {
                                    Path relativePath = tempExtractDir.relativize(source);
                                    Path target = dhFolder.resolve(relativePath);
                                    Files.createDirectories(target.getParent());
                                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException e) {
                                    LOGGER.error("Failed to move file: " + source, e);
                                }
                            });

                    deleteDirectoryRecursively(tempExtractDir);
                    Files.deleteIfExists(tempFile);

                    config.hasDownloadedLODs = true;
                    config.hasDeclined = false;
                    config.save();

                    LOGGER.info("LOD installation completed successfully");

                } catch (Exception e) {
                    LOGGER.error("Error during installation:", e);
                    client.execute(() -> {
                        Text errorMessage = Text.literal("Error installing LODs: " + e.getMessage())
                                .formatted(Formatting.RED);
                        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(errorMessage);
                    });
                } finally {
                    try {
                        LOGGER.info("Performing final cleanup...");
                        if (Files.exists(tempExtractDir)) {
                            deleteDirectoryRecursively(tempExtractDir);
                        }
                        Files.deleteIfExists(tempFile);
                        LOGGER.info("Final cleanup completed");
                    } catch (Exception cleanupEx) {
                        LOGGER.error("Failed to clean up temporary files:", cleanupEx);
                    }
                    isCurrentlyDownloading = false;
                }
            });

        } catch (Exception e) {
            LOGGER.error("Error during pre-extraction:", e);
            try {
                LOGGER.info("Cleaning up after extraction failure...");
                deleteDirectoryRecursively(tempExtractDir);
                Files.deleteIfExists(tempFile);
                LOGGER.info("Cleanup after failure completed");
            } catch (Exception cleanupEx) {
                LOGGER.error("Failed to clean up after extraction failure:", cleanupEx);
            }
            sendProgressMessage(client, "Error preparing LOD files: " + e.getMessage(), Formatting.RED);
            isCurrentlyDownloading = false;
        }
    }

    private void cleanupExistingFiles(Path dhFolder) {
        LOGGER.info("Cleaning up existing LOD files...");
        if (Files.exists(dhFolder)) {
            // Try multiple times with delays
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    deleteDirectoryRecursively(dhFolder);
                    LOGGER.info("Successfully cleaned up files on attempt " + attempt);
                    return;
                } catch (Exception e) {
                    LOGGER.warn("Cleanup attempt " + attempt + " failed, waiting before retry...");
                    try {
                        Thread.sleep(1000); // Wait a second between attempts
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    public void onYesCommand(MinecraftClient client) {
        downloadAndInstallLODs(client);
    }

    public void onNoCommand(MinecraftClient client) {
        config.hasDeclined = true;
        try {
            config.save();
        } catch (IOException e) {
            LOGGER.error("Failed to save config:", e);
        }

        client.player.sendMessage(
                Text.literal("You can always download the LODs later by typing /wynn_lod_yes")
                        .formatted(Formatting.YELLOW)
        );
    }

    public void forceDownload(MinecraftClient client) {
        config.hasDownloadedLODs = false;
        config.hasDeclined = false;
        try {
            config.save();
        } catch (IOException e) {
            LOGGER.error("Failed to save config during force download:", e);
        }
        downloadAndInstallLODs(client);
    }

    private void downloadAndInstallLODs(MinecraftClient client) {
        if (isCurrentlyDownloading) {
            LOGGER.warn("Download already in progress, ignoring new request");
            sendProgressMessage(client, "Download already in progress!", Formatting.RED);
            return;
        }

        Thread downloadThread = new Thread(() -> {
            isCurrentlyDownloading = true;
            Path tempFile = null;

            try {
                Path configDir = FabricLoader.getInstance().getConfigDir().resolve("wynnlodgrabber");
                Path dhFolder = FabricLoader.getInstance().getGameDir().resolve(DH_FOLDER);

                Files.createDirectories(configDir);

                LOGGER.info("Starting LOD download process");
                sendProgressMessage(client, "Starting Wynncraft LODs download...", Formatting.YELLOW);

                URL url = new URL(GITHUB_RELEASE_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "WynnLODGrabber Mod");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);

                int responseCode = connection.getResponseCode();
                LOGGER.info("Server response code: " + responseCode);

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Server returned response code: " + responseCode);
                }

                int fileSize = connection.getContentLength();
                LOGGER.info("Expected file size: " + fileSize + " bytes");

                tempFile = configDir.resolve("download_temp.zip");
                LOGGER.info("Created temporary file: " + tempFile);

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

                        int currentProgress = (int)((totalBytesRead * 100) / fileSize);
                        if (currentProgress >= lastProgressUpdate + 10) {
                            lastProgressUpdate = currentProgress;

                            long elapsedTime = System.currentTimeMillis() - startTime;
                            double speedMBps = (totalBytesRead / 1024.0 / 1024.0) / (elapsedTime / 1000.0);

                            sendProgressMessage(client,
                                    String.format("Download progress: %d%% (%.1f MB/s)",
                                            currentProgress, speedMBps),
                                    Formatting.AQUA);

                            if (currentProgress >= 90 && lastProgressUpdate < 90) {
                                sendProgressMessage(client,
                                        "Download almost complete! You will be disconnected shortly to install the LODs...",
                                        Formatting.GOLD);
                            }
                        }
                    }

                    long downloadedSize = Files.size(tempFile);
                    if (downloadedSize != fileSize) {
                        throw new IOException(String.format(
                                "Downloaded file size (%d) does not match expected size (%d)",
                                downloadedSize, fileSize));
                    }
                }

                LOGGER.info("Download completed successfully, file size: " + Files.size(tempFile) + " bytes");
                sendProgressMessage(client, "Download complete! You will be disconnected in 5 seconds to finish the installation.", Formatting.YELLOW);

                if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
                    throw new IOException("Downloaded file is missing or empty");
                }

                disconnectAndInstall(client, dhFolder, tempFile);

            } catch (IOException e) {
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                LOGGER.error("Download failed: " + errorMessage, e);
                sendProgressMessage(client,
                        "Error downloading LODs: " + errorMessage + "\nTry using /wynn_lod_force to retry the download.",
                        Formatting.RED);

                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                        LOGGER.info("Cleaned up temporary file after download failure");
                    } catch (IOException ioE) {
                        LOGGER.error("Failed to delete temporary file", ioE);
                    }
                }

                isCurrentlyDownloading = false;
            } catch (Exception e) {
                LOGGER.error("Unexpected error during download process:", e);
                sendProgressMessage(client,
                        "Unexpected error occurred. Please check the logs and try again.",
                        Formatting.RED);

                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                        LOGGER.info("Cleaned up temporary file after unexpected error");
                    } catch (IOException ioE) {
                        LOGGER.error("Failed to delete temporary file", ioE);
                    }
                }

                isCurrentlyDownloading = false;
            }
        });

        downloadThread.setName("WynnLOD-Downloader");
        LOGGER.info("Starting download thread");
        downloadThread.start();
    }
}