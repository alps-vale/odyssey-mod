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
            if (args.length != 4) throw new IOException("Expected target, parent PID, start time and transaction ID");
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
            apply(target, UpdateManifest.releaseKey(), args[3]);
        } catch (Exception error) {
            System.err.println("Odyssey update not installed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static Path directory(Path target) {
        return target.resolveSibling(".odyssey-update");
    }

    public static void apply(Path target, java.security.PublicKey key) throws Exception {
        apply(target, key, null);
    }

    public static void apply(Path target, java.security.PublicKey key, String expectedTransaction) throws Exception {
        target = target.toAbsolutePath().normalize();
        var directory = directory(target);
        validatePaths(target, directory);
        try (var channel = FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another updater owns this transaction");
            var journal = load(directory.resolve("pending.properties"));
            if (expectedTransaction != null && !expectedTransaction.equals(journal.getProperty("transactionId")))
                throw new IOException("Update transaction was replaced");
            if (!target.toString().equals(journal.getProperty("target")))
                throw new IOException("Update target changed");
            var manifest = UpdateManifest.verify(UpdateManifest.readLimited(directory.resolve("update.manifest"), UpdateManifest.MAX_MANIFEST),
                    UpdateManifest.readLimited(directory.resolve("update.manifest.sig"), 64), key);
            String current = UpdateManifest.digest(target);
            // A crash after the atomic move but before the receipt is recoverable without replacing again.
            if (current.equals(manifest.sha256())) {
                finish(directory, manifest);
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
                    finish(directory, manifest);
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

    public enum PendingState { NONE, PENDING, COMPLETED, DISCARDED }

    /** Drop only obsolete transaction state; never undo a manual installation or remove its backup. */
    public static PendingState reconcile(Path target, java.security.PublicKey key) throws Exception {
        Path directory = directory(target);
        Path pending = directory.resolve("pending.properties");
        if (!Files.exists(pending)) return PendingState.NONE;
        validatePaths(target, directory);
        try (var channel = FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another updater owns this transaction");
            if (!Files.exists(pending)) return PendingState.NONE;
            UpdateManifest manifest;
            boolean completed;
            try {
                var journal = load(pending);
                if (!target.toString().equals(journal.getProperty("target"))) throw new IOException("Target changed");
                manifest = UpdateManifest.verify(UpdateManifest.readLimited(directory.resolve("update.manifest"),
                                UpdateManifest.MAX_MANIFEST),
                        UpdateManifest.readLimited(directory.resolve("update.manifest.sig"), 64), key);
                String current = UpdateManifest.digest(target);
                completed = current.equals(manifest.sha256());
                if (!completed) {
                    if (!current.equals(journal.getProperty("previousSha256"))) throw new IOException("Manual installation");
                    manifest.verifyJar(directory.resolve("pending.jar"));
                }
            } catch (IOException | java.security.GeneralSecurityException invalid) {
                Files.delete(pending);
                return PendingState.DISCARDED;
            }
            // A valid completed transaction must survive transient receipt-write failures.
            if (completed) {
                finish(directory, manifest);
                return PendingState.COMPLETED;
            }
            return PendingState.PENDING;
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
                "update.manifest", "update.manifest.sig", "installed.txt", "installed.sha256", "helper.jar", "helper.log"}) {
            if (Files.isSymbolicLink(directory.resolve(name))) throw new IOException("Unsafe update path");
        }
    }

    public static Properties load(Path path) throws IOException {
        if (Files.size(path) > UpdateManifest.MAX_MANIFEST) throw new IOException("Oversized update journal");
        var result = new Properties();
        try (var input = Files.newInputStream(path)) {
            result.load(input);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed update journal", malformed);
        }
        String transaction = result.getProperty("transactionId");
        try {
            if (transaction == null || !java.util.UUID.fromString(transaction).toString().equals(transaction))
                throw new IllegalArgumentException("Missing or noncanonical transaction ID");
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid update transaction ID", malformed);
        }
        return result;
    }

    /** Keep rollback detection after the one-time installation notice has been consumed. */
    public static boolean wasRolledBack(Path target) throws IOException, java.security.GeneralSecurityException {
        Path directory = directory(target);
        Path receipt = directory.resolve("installed.sha256");
        if (!Files.exists(receipt)) return false;
        validatePaths(target, directory);
        String installed = new String(UpdateManifest.readLimited(receipt, 64),
                java.nio.charset.StandardCharsets.UTF_8);
        String current = UpdateManifest.digest(target);
        Path backup = directory.resolve("previous.jar");
        return !installed.equals(current) && Files.isRegularFile(backup)
                && UpdateManifest.digest(backup).equals(current);
    }

    /** An explicit opt-in acknowledges the restored version without deleting the backup. */
    public static void acknowledgeRollback(Path target) throws IOException, java.security.GeneralSecurityException {
        // Enabling automatic updates on a healthy installation must retain future rollback detection.
        if (wasRolledBack(target)) Files.deleteIfExists(directory(target).resolve("installed.sha256"));
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

    private static void finish(Path directory, UpdateManifest manifest) throws IOException {
        atomicWrite(directory.resolve("installed.sha256"), manifest.sha256().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        atomicWrite(directory.resolve("installed.txt"), manifest.version().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.deleteIfExists(directory.resolve("pending.properties"));
    }
}
