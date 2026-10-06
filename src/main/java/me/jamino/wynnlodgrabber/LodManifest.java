package me.jamino.wynnlodgrabber;

import com.google.gson.Gson;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Describes the downloadable LOD packages: where to get them, how big they are and their SHA-256.
 * <p>
 * The manifest is {@code manifest.json} on the repository's master branch; the zips it points to live on a LOD
 * GitHub release. If it
 * cannot be fetched we fall back to {@link #fallback()}, which mirrors the currently released files, so
 * downloads keep working (and stay checksum-verified) without network access to the manifest.
 */
public final class LodManifest {
    /** Stable location: edit manifest.json on master to publish new LODs without releasing a new mod version. */
    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/DrBiznes/WynnLODGrabber/master/manifest.json";

    private static final Gson GSON = new Gson();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static volatile LodManifest current = fallback();

    public static final class Package {
        public String url = "";
        /** Size of the zip in bytes. */
        public long size;
        /** Total uncompressed size in bytes (used for the free-disk-space check). Optional. */
        public long extractedSize;
        public String sha256 = "";

        Package() {}

        Package(String url, long size, long extractedSize, String sha256) {
            this.url = url;
            this.size = size;
            this.extractedSize = extractedSize;
            this.sha256 = sha256;
        }
    }

    /** Identifier of the LOD release, e.g. the release tag. Compared to detect available updates. */
    public String version = "";
    /** Keyed by {@code "dh"} / {@code "voxy"}. */
    public Map<String, Package> packages = new LinkedHashMap<>();

    public Package get(String mod) {
        return packages == null ? null : packages.get(mod);
    }

    public static LodManifest current() {
        return current;
    }

    /** Fetches the published manifest. Returns null (and keeps the previous one) if unavailable or invalid. */
    public static LodManifest fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(MANIFEST_URL).toURL().openConnection();
            connection.setRequestProperty("User-Agent", "WynnLODGrabber Mod");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Wynnlodgrabber.LOGGER.warn("LOD manifest returned HTTP {}", connection.getResponseCode());
                return null;
            }
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                LodManifest manifest = GSON.fromJson(reader, LodManifest.class);
                if (manifest != null && manifest.isValid()) {
                    current = manifest;
                    return manifest;
                }
                Wynnlodgrabber.LOGGER.warn("LOD manifest was malformed, ignoring it");
            }
        } catch (Exception e) {
            Wynnlodgrabber.LOGGER.warn("Could not fetch LOD manifest: {}", e.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    private boolean isValid() {
        if (version == null || version.isBlank() || packages == null || packages.isEmpty()) return false;
        for (Package pkg : packages.values()) {
            if (pkg == null || pkg.url == null || !pkg.url.startsWith("https://")) return false;
            if (pkg.size <= 0 || pkg.sha256 == null || !SHA256.matcher(pkg.sha256).matches()) return false;
        }
        return true;
    }

    private static LodManifest fallback() {
        String base = "https://github.com/DrBiznes/WynnLODGrabber/releases/download/LOD-04-19-26/";
        LodManifest manifest = new LodManifest();
        manifest.version = "LOD-04-19-26";
        manifest.packages.put("dh", new Package(base + "wynnlodDHfruma.zip",
                1426025322L, 1708158976L,
                "ea680caf0c81481d06f0b533081fd6e43206c0270add5ebea8399096b4cce0cb"));
        manifest.packages.put("voxy", new Package(base + "frumavoxylods.zip",
                592327286L, 619917136L,
                "2b494667869473c0133cc3de7c25b9b41b52382b1de79c7e79068139aad3f725"));
        return manifest;
    }
}
