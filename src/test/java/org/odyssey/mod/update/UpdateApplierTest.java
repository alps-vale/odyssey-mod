package org.odyssey.mod.update;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
