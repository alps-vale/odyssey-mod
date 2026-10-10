import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** JDK-only publisher: the private key is injected only into this step. */
class ReleaseSigner {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("generate")) {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            // This mode is run by a private credential worker, never printed in chat.
            System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            System.out.println(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Expected manifest and public key");
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(System.getenv("ODYSSEY_UPDATE_SIGNING_KEY"))));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(bytes);
        byte[] signature = signer.sign();
        var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(
                Base64.getMimeDecoder().decode(Files.readAllBytes(Path.of(args[1])))));
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(bytes);
        if (!verifier.verify(signature)) throw new SignatureException("Signing key does not match the pinned key");
        Files.write(Path.of(args[0] + ".sig"), signature);
        System.out.println("Release manifest signed and verified.");
    }
}
