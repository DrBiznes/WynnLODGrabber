package me.jamino.wynnlodgrabber;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson gson = new Gson();
    private static Path configPath;
    private static Path configDir;

    public boolean hasBeenAsked = false;
    public boolean hasDeclined = false;
    public boolean hasDownloadedLODs = false;
    public String currentLODVersion = "";

    public static Config load(Path path) throws IOException {
        configPath = path;
        configDir = path.getParent();

        // Ensure the config directory exists
        Files.createDirectories(configDir);

        Config config;
        if (Files.isRegularFile(configPath)) {
            config = gson.fromJson(new String(Files.readAllBytes(configPath)), Config.class);
        } else {
            config = new Config();
            // Create subdirectories needed for the mod
            Files.createDirectories(configDir.resolve("temp_extract"));
            // Create and save initial config file
            Files.createFile(configPath);
            Files.write(configPath, gson.toJson(config).getBytes());
        }

        return config;
    }

    public void save() throws IOException {
        // Ensure directory exists before saving
        Files.createDirectories(configPath.getParent());
        Files.write(configPath, gson.toJson(this).getBytes());
    }
}