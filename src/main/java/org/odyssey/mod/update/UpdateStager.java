package org.odyssey.mod.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Download and validate before committing a transaction; callers launch the helper only on success. */
public final class UpdateStager {
    private UpdateStager() {}

    public static boolean stage(Path target, UpdateManifest manifest, byte[] bytes, byte[] signature,
                                UpdateTransport transport, Consumer<Path> verifyMetadata,
                                BooleanSupplier commitAllowed) throws Exception {
        Path directory = UpdateApplier.directory(target);
        UpdateApplier.validatePaths(target, directory);
        try (var channel = FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another update is being prepared");
            if (Files.exists(directory.resolve("pending.properties"))) throw new IOException("An update is already pending");
            Path staged = directory.resolve("pending.jar");
            transport.download(manifest.jarUrl(), staged, manifest.size());
            manifest.verifyJar(staged);
            verifyMetadata.accept(staged);
            UpdateApplier.atomicWrite(directory.resolve("update.manifest"), bytes);
            UpdateApplier.atomicWrite(directory.resolve("update.manifest.sig"), signature);
            var properties = new Properties();
            properties.setProperty("target", target.toString());
            properties.setProperty("previousSha256", UpdateManifest.digest(target));
            properties.setProperty("transactionId", UUID.randomUUID().toString());
            var journal = new ByteArrayOutputStream();
            properties.store(journal, "Odyssey pending update");
            // Observe opt-out after all potentially slow work, immediately before the commit point.
            if (!commitAllowed.getAsBoolean()) {
                Files.delete(staged);
                return false;
            }
            UpdateApplier.atomicWrite(directory.resolve("pending.properties"), journal.toByteArray());
            return true;
        }
    }
}
