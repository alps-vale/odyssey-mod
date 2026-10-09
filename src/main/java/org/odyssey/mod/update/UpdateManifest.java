package org.odyssey.mod.update;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.ZipFile;

/** The signature covers the exact manifest bytes, not a reserialized object. */
public record UpdateManifest(String version, URI jarUrl, long size, String sha256,
                             String metadataSha256, Map<String, String> requirements) {
    public static final int MAX_MANIFEST = 16_384;
    public static final long MAX_JAR = 32 * 1024 * 1024;
    public static final String RELEASES = "https://github.com/alps-vale/odyssey-mod/releases/";
    private static final Set<String> DEPENDENCIES = Set.of(
            "minecraft", "java", "fabricloader", "fabric-api", "fabric-language-kotlin");

    public static PublicKey releaseKey() throws IOException, GeneralSecurityException {
        try (var input = UpdateManifest.class.getResourceAsStream("/odyssey-update.pub")) {
            if (input == null) throw new IOException("Missing release verification key");
            return publicKey(input.readNBytes(1024));
        }
    }

    public static PublicKey publicKey(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                Base64.getMimeDecoder().decode(encoded)));
    }

    public static byte[] readLimited(Path path, int maximum) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Oversized update state");
            return bytes;
        }
    }

    public static UpdateManifest verify(byte[] bytes, byte[] signature, PublicKey key)
            throws IOException, GeneralSecurityException {
        if (bytes.length == 0 || bytes.length > MAX_MANIFEST || signature.length != 64)
            throw new IOException("Invalid update manifest size");
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(bytes);
        if (!verifier.verify(signature)) throw new SignatureException("Invalid release signature");
        var fields = new HashMap<String, String>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            int separator = line.indexOf('=');
            if (separator < 1 || fields.putIfAbsent(line.substring(0, separator),
                    line.substring(separator + 1)) != null) throw new IOException("Invalid manifest fields");
        }
        var expected = new HashSet<>(Set.of("format", "version", "url", "size", "sha256", "metadataSha256"));
        DEPENDENCIES.forEach(id -> expected.add("requires." + id));
        if (!fields.keySet().equals(expected) || !"1".equals(fields.get("format")))
            throw new IOException("Unsupported update manifest");
        String version = fields.get("version");
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw new IOException("Only stable releases can be installed automatically");
        URI url;
        long size;
        try {
            url = URI.create(fields.get("url"));
            size = Long.parseLong(fields.get("size"));
        } catch (IllegalArgumentException error) { throw new IOException("Invalid artifact location", error); }
        if (!url.toString().equals(RELEASES + "download/v" + version + "/odyssey-mod.jar")
                || size < 1 || size > MAX_JAR
                || !fields.get("sha256").matches("[0-9a-f]{64}")
                || !fields.get("metadataSha256").matches("[0-9a-f]{64}"))
            throw new IOException("Invalid artifact identity");
        var requirements = new HashMap<String, String>();
        for (String id : DEPENDENCIES) {
            String value = fields.get("requires." + id);
            if (value.isBlank() || value.length() > 128 || value.indexOf('\r') >= 0)
                throw new IOException("Invalid compatibility requirement");
            requirements.put(id, value);
        }
        return new UpdateManifest(version, url, size, fields.get("sha256"),
                fields.get("metadataSha256"), Map.copyOf(requirements));
    }

    public void verifyJar(Path jar) throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(jar, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.size(jar) != size || !digest(jar).equals(sha256))
            throw new IOException("Release JAR failed verification");
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null || entry.getSize() > MAX_MANIFEST)
                throw new IOException("Missing Fabric metadata");
            try (var input = zip.getInputStream(entry)) {
                byte[] metadata = input.readNBytes(MAX_MANIFEST + 1);
                if (metadata.length > MAX_MANIFEST || !digest(metadata).equals(metadataSha256))
                    throw new IOException("Fabric metadata failed verification");
            }
            if (zip.getEntry("org/odyssey/mod/OdysseyMod.class") == null)
                throw new IOException("Not a runnable Odyssey JAR");
        }
    }

    public static String digest(Path path) throws IOException, GeneralSecurityException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String digest(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
