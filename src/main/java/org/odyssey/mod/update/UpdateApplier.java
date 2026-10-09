package org.odyssey.mod.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Runs without Fabric or Kotlin, using the same Java runtime as Minecraft. */
public final class UpdateApplier {
    private UpdateApplier() {}

    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IOException("Expected target, parent PID and start time");
            var target = Path.of(args[0]);
            long pid = Long.parseLong(args[1]);
            Instant start = Instant.parse(args[2]);
            var parent = ProcessHandle.of(pid);
            if (parent.isPresent()) {
                // Refuse a reused or unverifiable PID. Never kill the game to install an update.
                if (!parent.get().info().startInstant().orElseThrow().equals(start))
                    throw new IOException("Game process identity changed");
                parent.get().onExit().get(7, TimeUnit.DAYS);
            }
            apply(target, UpdateManifest.releaseKey());
        } catch (Exception error) {
            System.err.println("Odyssey update not installed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static Path directory(Path target) {
        return target.resolveSibling(".odyssey-update");
    }

    public static void apply(Path target, java.security.PublicKey key) throws Exception {
        target = target.toAbsolutePath().normalize();
        var directory = directory(target);
        validatePaths(target, directory);
        try (var channel = FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another updater owns this transaction");
            var journal = load(directory.resolve("pending.properties"));
            if (!target.toString().equals(journal.getProperty("target")))
                throw new IOException("Update target changed");
            var manifest = UpdateManifest.verify(UpdateManifest.readLimited(directory.resolve("update.manifest"), UpdateManifest.MAX_MANIFEST),
                    UpdateManifest.readLimited(directory.resolve("update.manifest.sig"), 64), key);
            String current = UpdateManifest.digest(target);
            // A crash after the atomic move but before the receipt is recoverable without replacing again.
            if (current.equals(manifest.sha256())) {
                finish(directory, manifest.version());
                return;
            }
            if (!current.equals(journal.getProperty("previousSha256")))
                throw new IOException("Installed JAR changed; refusing to overwrite it");
            Path staged = directory.resolve("pending.jar");
            manifest.verifyJar(staged);
            Path backup = directory.resolve("previous.jar");
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            force(backup);
            if (!UpdateManifest.digest(backup).equals(current)) throw new IOException("Backup verification failed");
            IOException failure = null;
            for (int attempt = 0; attempt < 30; attempt++) {
                try {
                    // Do not implement a delete-first or non-atomic fallback.
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    force(target);
                    finish(directory, manifest.version());
                    return;
                } catch (AtomicMoveNotSupportedException unsupported) {
                    throw unsupported;
                } catch (IOException blocked) {
                    failure = blocked;
                    // If the move succeeded but writing the receipt failed, do not try a missing stage again.
                    if (Files.isRegularFile(target) && UpdateManifest.digest(target).equals(manifest.sha256()))
                        throw blocked;
                    if (!UpdateManifest.digest(target).equals(current)) throw blocked;
                    Thread.sleep(1_000);
                }
            }
            throw failure;
        }
    }

    public static void validatePaths(Path target, Path directory) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)
                || !target.toRealPath().equals(target.toAbsolutePath().normalize()))
            throw new IOException("Updates require a regular installed JAR");
        Files.createDirectories(directory);
        if (!Files.getFileStore(target).equals(Files.getFileStore(directory)))
            throw new IOException("Update staging must use the same filesystem");
        for (String name : new String[]{"lock", "pending.jar", "previous.jar", "pending.properties",
                "update.manifest", "update.manifest.sig", "installed.txt", "helper.jar", "helper.log"}) {
            if (Files.isSymbolicLink(directory.resolve(name))) throw new IOException("Unsafe update path");
        }
    }

    public static Properties load(Path path) throws IOException {
        if (Files.size(path) > UpdateManifest.MAX_MANIFEST) throw new IOException("Oversized update journal");
        var result = new Properties();
        try (var input = Files.newInputStream(path)) { result.load(input); }
        return result;
    }

    public static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        if (Files.isSymbolicLink(temporary)) throw new IOException("Unsafe temporary path");
        Files.write(temporary, bytes);
        force(temporary);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void finish(Path directory, String version) throws IOException {
        atomicWrite(directory.resolve("installed.txt"), version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.deleteIfExists(directory.resolve("pending.properties"));
    }
}
