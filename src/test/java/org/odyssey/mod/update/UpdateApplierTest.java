package org.odyssey.mod.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.net.http.HttpClient;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateApplierTest {
    @TempDir Path root;
    KeyPair key;
    Path target;
    Path directory;
    byte[] metadata;
    byte[] manifest;
    byte[] signature;
    byte[] previous;

    @BeforeEach void prepare() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path mods = Files.createDirectories(root.resolve("Jolly instance — é/mods"));
        target = mods.resolve("My Odyssey.jar").toAbsolutePath().normalize();
        previous = "previous installed version".getBytes(StandardCharsets.UTF_8);
        Files.write(target, previous);
        // macOS temp roots may begin at /var (a symlink to /private/var).
        // The runtime resolves Fabric's installed JAR to its real path too.
        target = target.toRealPath();
        Files.writeString(mods.resolve("unrelated-mod.jar"), "leave this alone");
        directory = Files.createDirectory(UpdateApplier.directory(target));
        metadata = ("{\"id\":\"odyssey\",\"version\":\"0.2.0\",\"environment\":\"client\",\"depends\":{"
                + "\"minecraft\":\"1.21.11\",\"java\":\">=25\",\"fabricloader\":\">=0.19.3\","
                + "\"fabric-api\":\">=0.141.6+1.21.11\",\"fabric-language-kotlin\":\">=1.13.13+kotlin.2.4.10\"}}")
                .getBytes(StandardCharsets.UTF_8);
        try (var zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("pending.jar")))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json")); zip.write(metadata); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("org/odyssey/mod/OdysseyMod.class")); zip.write(new byte[]{1}); zip.closeEntry();
        }
        Path staged = directory.resolve("pending.jar");
        manifest = ("format=1\nversion=0.2.0\nurl=" + UpdateManifest.RELEASES + "download/v0.2.0/odyssey-mod.jar\n"
                + "size=" + Files.size(staged) + "\nsha256=" + UpdateManifest.digest(staged) + "\n"
                + "metadataSha256=" + UpdateManifest.digest(metadata) + "\nrequires.minecraft=1.21.11\n"
                + "requires.java=>=25\nrequires.fabricloader=>=0.19.3\nrequires.fabric-api=>=0.141.6+1.21.11\n"
                + "requires.fabric-language-kotlin=>=1.13.13+kotlin.2.4.10\n").getBytes(StandardCharsets.UTF_8);
        var signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(manifest);
        signature = signer.sign();
        Files.write(directory.resolve("update.manifest"), manifest);
        Files.write(directory.resolve("update.manifest.sig"), signature);
        var journal = new Properties();
        journal.setProperty("target", target.toString());
        journal.setProperty("previousSha256", UpdateManifest.digest(target));
        journal.setProperty("transactionId", UUID.randomUUID().toString());
        try (var output = Files.newOutputStream(directory.resolve("pending.properties"))) { journal.store(output, null); }
    }

    @Test void rejectsTamperingWrongKeysAndTruncatedArtifacts() throws Exception {
        byte[] corrupted = manifest.clone(); corrupted[10] ^= 1;
        assertThrows(SignatureException.class, () -> UpdateManifest.verify(corrupted, signature, key.getPublic()));
        assertThrows(SignatureException.class, () -> UpdateManifest.verify(manifest, signature,
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()));
        Files.writeString(directory.resolve("pending.jar"), "truncated");
        assertThrows(IOException.class, () -> UpdateApplier.apply(target, key.getPublic()));
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertTrue(Files.exists(directory.resolve("pending.properties")));
    }

    @Test void preservesExactTargetBackupAndOtherMods() throws Exception {
        String expected = UpdateManifest.verify(manifest, signature, key.getPublic()).sha256();
        UpdateApplier.apply(target, key.getPublic());
        assertEquals(expected, UpdateManifest.digest(target));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
        assertEquals("leave this alone", Files.readString(target.resolveSibling("unrelated-mod.jar")));
        assertFalse(Files.exists(directory.resolve("pending.properties")));
        assertEquals("0.2.0", Files.readString(directory.resolve("installed.txt")));
    }

    @Test void reconcilesCrashAfterReplacementBeforeReceipt() throws Exception {
        Files.copy(target, directory.resolve("previous.jar"));
        Files.move(directory.resolve("pending.jar"), target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        UpdateApplier.apply(target, key.getPublic());
        assertFalse(Files.exists(directory.resolve("pending.properties")));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
    }

    @Test void refusesChangedTargetAndConcurrentTransaction() throws Exception {
        Files.writeString(target, "a manually installed version");
        assertThrows(IOException.class, () -> UpdateApplier.apply(target, key.getPublic()));
        assertEquals("a manually installed version", Files.readString(target));
        Files.write(target, previous);
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    () -> UpdateApplier.apply(target, key.getPublic()));
        }
        assertArrayEquals(previous, Files.readAllBytes(target));
    }

    @Test void manualRecoveryAndCorruptedStageDoNotPermanentlyBlockFutureUpdates() throws Exception {
        Files.writeString(target, "a newer manual installation");
        assertEquals(UpdateApplier.PendingState.DISCARDED, UpdateApplier.reconcile(target, key.getPublic()));
        assertEquals("a newer manual installation", Files.readString(target));
        assertEquals(UpdateApplier.PendingState.NONE, UpdateApplier.reconcile(target, key.getPublic()));
        Files.write(target, previous);
        var journal = new Properties();
        journal.setProperty("target", target.toString());
        journal.setProperty("previousSha256", UpdateManifest.digest(target));
        journal.setProperty("transactionId", UUID.randomUUID().toString());
        try (var output = Files.newOutputStream(directory.resolve("pending.properties"))) { journal.store(output, null); }
        Files.delete(directory.resolve("pending.jar"));
        assertEquals(UpdateApplier.PendingState.DISCARDED, UpdateApplier.reconcile(target, key.getPublic()));
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertFalse(Files.exists(directory.resolve("pending.properties")));
    }

    @Test void oldHelperCannotApplyAReplacementTransaction() throws Exception {
        var journal = UpdateApplier.load(directory.resolve("pending.properties"));
        String oldId = journal.getProperty("transactionId");
        journal.setProperty("transactionId", UUID.randomUUID().toString());
        try (var output = Files.newOutputStream(directory.resolve("pending.properties"))) { journal.store(output, null); }
        assertThrows(IOException.class, () -> UpdateApplier.apply(target, key.getPublic(), oldId));
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertTrue(Files.exists(directory.resolve("pending.properties")));
        UpdateApplier.apply(target, key.getPublic(), journal.getProperty("transactionId"));
    }

    @Test void malformedPropertyEscapesAreDiscardedWithoutTouchingInstalledJarOrBackup() throws Exception {
        Files.writeString(directory.resolve("pending.properties"), "target=\\u12\n");
        Files.write(directory.resolve("previous.jar"), previous);
        assertThrows(IOException.class, () -> UpdateApplier.load(directory.resolve("pending.properties")));
        assertEquals(UpdateApplier.PendingState.DISCARDED, UpdateApplier.reconcile(target, key.getPublic()));
        assertEquals(UpdateApplier.PendingState.NONE, UpdateApplier.reconcile(target, key.getPublic()));
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
    }

    @Test void manualRollbackRemainsDetectableAfterNoticeUntilExplicitlyAcknowledged() throws Exception {
        UpdateApplier.apply(target, key.getPublic());
        assertFalse(UpdateApplier.wasRolledBack(target));
        Files.delete(directory.resolve("installed.txt")); // The client already showed the installation notice.
        Files.copy(directory.resolve("previous.jar"), target, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(UpdateApplier.wasRolledBack(target));
        assertTrue(UpdateApplier.wasRolledBack(target)); // A subsequent startup must still pause automatic updates.
        UpdateApplier.acknowledgeRollback(target);
        assertFalse(UpdateApplier.wasRolledBack(target));
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
    }

    @Test void incompleteTransactionIdsAreDiscardedWithoutBlockingReplacementTransactions() throws Exception {
        Path pending = directory.resolve("pending.properties");
        var journal = UpdateApplier.load(pending);
        Files.write(directory.resolve("previous.jar"), previous);
        for (String id : new String[]{"", "not-a-uuid", "1-1-1-1-1"}) {
            journal.setProperty("transactionId", id);
            try (var output = Files.newOutputStream(pending)) { journal.store(output, null); }
            assertThrows(IOException.class, () -> UpdateApplier.load(pending));
            assertEquals(UpdateApplier.PendingState.DISCARDED, UpdateApplier.reconcile(target, key.getPublic()));
            assertFalse(Files.exists(pending));
        }
        journal.remove("transactionId");
        try (var output = Files.newOutputStream(pending)) { journal.store(output, null); }
        assertEquals(UpdateApplier.PendingState.DISCARDED, UpdateApplier.reconcile(target, key.getPublic()));
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
        journal.setProperty("transactionId", UUID.randomUUID().toString());
        try (var output = Files.newOutputStream(pending)) { journal.store(output, null); }
        assertEquals(UpdateApplier.PendingState.PENDING, UpdateApplier.reconcile(target, key.getPublic()));
        UpdateApplier.apply(target, key.getPublic());
        assertFalse(Files.exists(pending));
    }

    @Test void enablingAutomaticUpdatesOnHealthyInstallPreservesFutureRollbackDetection() throws Exception {
        UpdateApplier.apply(target, key.getPublic());
        UpdateApplier.acknowledgeRollback(target);
        assertTrue(Files.exists(directory.resolve("installed.sha256")));
        Files.copy(directory.resolve("previous.jar"), target, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(UpdateApplier.wasRolledBack(target));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
    }

    @Test void completedTransactionSurvivesReceiptWriteFailureAndFinishesOnRetry() throws Exception {
        String expected = UpdateManifest.verify(manifest, signature, key.getPublic()).sha256();
        Files.copy(target, directory.resolve("previous.jar"));
        Files.move(directory.resolve("pending.jar"), target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        // A real filesystem write failure, portable across the native CI runners.
        Files.createDirectory(directory.resolve("installed.sha256"));
        assertThrows(IOException.class, () -> UpdateApplier.reconcile(target, key.getPublic()));
        assertTrue(Files.exists(directory.resolve("pending.properties")));
        assertEquals(expected, UpdateManifest.digest(target));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
        Files.delete(directory.resolve("installed.sha256"));
        assertEquals(UpdateApplier.PendingState.COMPLETED, UpdateApplier.reconcile(target, key.getPublic()));
        assertEquals(expected, Files.readString(directory.resolve("installed.sha256")));
        assertFalse(Files.exists(directory.resolve("pending.properties")));
    }

    @Test void optOutDuringDownloadCancelsAutomaticTransactionBeforeCommit() throws Exception {
        optOutDuringDownload(true);
    }

    @Test void optOutDuringDownloadDoesNotCancelExplicitInstallation() throws Exception {
        optOutDuringDownload(false);
    }

    private void optOutDuringDownload(boolean automaticInstall) throws Exception {
        byte[] jar = Files.readAllBytes(directory.resolve("pending.jar"));
        Files.delete(directory.resolve("pending.jar"));
        Files.delete(directory.resolve("pending.properties"));
        var began = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var automatic = new AtomicBoolean(true);
        var executor = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jar", exchange -> {
            exchange.sendResponseHeaders(200, jar.length);
            try (var output = exchange.getResponseBody()) {
                output.write(jar, 0, 1); output.flush(); began.countDown();
                if (resume.await(10, TimeUnit.SECONDS)) output.write(jar, 1, jar.length - 1);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jar");
            var signed = UpdateManifest.verify(manifest, signature, key.getPublic());
            // The local HTTP fixture replaces only the download endpoint, not the signed transaction data.
            var local = new UpdateManifest(signed.version(), uri, signed.size(), signed.sha256(),
                    signed.metadataSha256(), signed.requirements());
            var transport = new UpdateTransport(HttpClient.newHttpClient(), candidate -> candidate.equals(uri),
                    Duration.ofSeconds(10));
            var result = executor.submit(() -> UpdateStager.stage(target, local, manifest, signature,
                    transport, staged -> assertTrue(Files.isRegularFile(staged)),
                    () -> !automaticInstall || automatic.get()));
            assertTrue(began.await(10, TimeUnit.SECONDS));
            automatic.set(false);
            resume.countDown();
            assertEquals(!automaticInstall, result.get(10, TimeUnit.SECONDS));
            assertArrayEquals(previous, Files.readAllBytes(target));
            assertEquals(!automaticInstall, Files.exists(directory.resolve("pending.properties")));
            if (!automaticInstall) {
                UpdateApplier.apply(target, key.getPublic());
                assertEquals(signed.sha256(), UpdateManifest.digest(target));
                assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
            }
        } finally {
            resume.countDown(); server.stop(0); executor.shutdownNow();
        }
    }

    @Test void standaloneHelpersWaitForActualParentExitAndHandleDuplicateLaunches() throws Exception {
        Process parent = startParent();
        Process first = null, second = null;
        try {
            Path helper = fixtureHelper();
            first = helper(helper, parent);
            second = helper(helper, parent);
            Thread.sleep(600);
            assertTrue(first.isAlive()); assertTrue(second.isAlive());
            assertArrayEquals(previous, Files.readAllBytes(target));
            parent.getOutputStream().write(1); parent.getOutputStream().flush();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            assertTrue(first.waitFor(15, TimeUnit.SECONDS));
            assertTrue(second.waitFor(15, TimeUnit.SECONDS));
            assertTrue(first.exitValue() == 0 || second.exitValue() == 0);
            assertEquals(UpdateManifest.verify(manifest, signature, key.getPublic()).sha256(), UpdateManifest.digest(target));
            assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
        } finally {
            parent.destroyForcibly();
            if (first != null && first.isAlive()) first.destroyForcibly();
            if (second != null && second.isAlive()) second.destroyForcibly();
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS) void waitsOutRealWindowsDeleteSharingLockAfterGameExits() throws Exception {
        var lockerBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$f=[System.IO.File]::Open($env:ODYSSEY_TEST_TARGET,[System.IO.FileMode]::Open,"
                + "[System.IO.FileAccess]::Read,[System.IO.FileShare]::Read);"
                + "[Console]::WriteLine('locked');[Console]::Out.Flush();[Console]::ReadLine()|Out-Null;"
                + "Start-Sleep -Seconds 2;$f.Dispose()");
        lockerBuilder.environment().put("ODYSSEY_TEST_TARGET", target.toString());
        Process locker = lockerBuilder.start();
        Process parent = null, updater = null;
        try {
            assertEquals("locked", new BufferedReader(new InputStreamReader(locker.getInputStream())).readLine());
            assertThrows(IOException.class, () -> Files.move(directory.resolve("pending.jar"), target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
            parent = startParent();
            updater = helper(fixtureHelper(), parent);
            parent.getOutputStream().write(1); parent.getOutputStream().flush();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            Thread.sleep(600);
            assertArrayEquals(previous, Files.readAllBytes(target));
            assertTrue(updater.isAlive());
            locker.getOutputStream().write('\n'); locker.getOutputStream().flush();
            assertTrue(updater.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, updater.exitValue(), new String(updater.getInputStream().readAllBytes()));
            assertArrayEquals(previous, Files.readAllBytes(directory.resolve("previous.jar")));
        } finally {
            locker.destroyForcibly();
            if (parent != null) parent.destroyForcibly();
            if (updater != null && updater.isAlive()) updater.destroyForcibly();
        }
    }

    private Process startParent() throws Exception {
        var parent = new ProcessBuilder(java(), "-cp", Path.of(WaitingGame.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString(),
                WaitingGame.class.getName()).start();
        assertEquals("ready", new BufferedReader(new InputStreamReader(parent.getInputStream())).readLine());
        return parent;
    }

    private Process helper(Path jar, Process parent) throws Exception {
        return new ProcessBuilder(java(), "-jar", jar.toString(), target.toString(),
                Long.toString(parent.pid()), parent.toHandle().info().startInstant().orElseThrow().toString(),
                UpdateApplier.load(directory.resolve("pending.properties")).getProperty("transactionId"))
                .redirectErrorStream(true).start();
    }

    private Path fixtureHelper() throws Exception {
        // Exercise the shipped standalone helper, replacing only its verification key with this test's key.
        Path fixture = root.resolve("fixture-helper.jar");
        try (var source = new JarFile(System.getProperty("odyssey.helper.jar"));
             var output = new JarOutputStream(Files.newOutputStream(fixture))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                output.putNextEntry(new JarEntry(entry.getName()));
                if (entry.getName().equals("odyssey-update.pub"))
                    output.write(Base64.getEncoder().encode(key.getPublic().getEncoded()));
                else try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
        }
        return fixture;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    public static class WaitingGame {
        public static void main(String[] args) throws Exception {
            System.out.println("ready"); System.out.flush(); System.in.read();
        }
    }
}
