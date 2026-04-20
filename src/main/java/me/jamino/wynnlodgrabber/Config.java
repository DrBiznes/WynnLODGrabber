package me.jamino.wynnlodgrabber;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson gson = new Gson();
    private static Path configPath;
    private static Path configDir;

    public boolean hasDownloadedDhLods   = false;
    public boolean hasDownloadedVoxyLods = false;
    public boolean hasDeclinedDh         = false;
    public boolean hasDeclinedVoxy       = false;
    public String  installedDhIp         = "";
    public String  installedVoxyIp       = "";
    public String  currentLODVersion     = "";

    public static Config load(Path path) throws IOException {
        configPath = path;
        configDir = path.getParent();

        Files.createDirectories(configDir);

        Config config;
        if (Files.isRegularFile(configPath)) {
            config = gson.fromJson(new String(Files.readAllBytes(configPath)), Config.class);
        } else {
            config = new Config();
            Files.createDirectories(configDir.resolve("temp_extract"));
            Files.createFile(configPath);
            Files.write(configPath, gson.toJson(config).getBytes());
        }

        return config;
    }

    public void save() throws IOException {
        Files.createDirectories(configPath.getParent());
        Files.write(configPath, gson.toJson(this).getBytes());
    }
}
