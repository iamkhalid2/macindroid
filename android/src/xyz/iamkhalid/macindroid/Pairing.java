package xyz.iamkhalid.macindroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairs with a Mac without cables, using numeric comparison like Bluetooth: an X25519 key exchange, then both
 * screens show a 6-digit code derived from it. The user checks they match and clicks Pair on the Mac, which then
 * proves it holds the same key by sending an encrypted HELLO. Mirrors Link.handlePairing in the Mac app.
 */
final class Pairing implements Runnable {
    interface Listener {
        void onCode(String code, String macName);

        void onFinished(boolean paired, String message);
    }

    private static final byte[] X25519_SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00};

    private final Context context;
    private final InetSocketAddress target;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Socket socket;

    private Pairing(Context context, InetSocketAddress target, Listener listener) {
        this.context = context.getApplicationContext();
        this.target = target;
        this.listener = listener;
    }

    static Pairing start(Context context, InetSocketAddress target, Listener listener) {
        Pairing p = new Pairing(context, target, listener);
        new Thread(p, "macindroid-pair").start();
        return p;
    }

    void cancel() {
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void run() {
        try (Socket s = new Socket()) {
            socket = s;
            s.connect(target, 5000);
            s.setSoTimeout(180_000); // the user is comparing codes
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

            KeyPair mine = KeyPairGenerator.getInstance("XDH").generateKeyPair(); // X25519 by default
            byte[] encoded = mine.getPublic().getEncoded();
            byte[] phonePub = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
            Proto.writeFrame(out, Proto.concat(Proto.PAIR_MAGIC, new byte[] {Proto.PAIR_REQUEST}, phonePub,
                    Proto.utf8(LinkService.deviceName(context))));

            byte[] reply = Proto.readFrame(in);
            if (!Proto.isPairing(reply)) throw new IllegalStateException("unexpected reply");
            if (reply[4] == Proto.PAIR_CLOSED) {
                finish(false, "Your Mac isn't ready to pair. In the Macindroid menu on the Mac, choose Pair a Phone…");
                return;
            }
            if (reply[4] != Proto.PAIR_CHALLENGE || reply.length < 5 + 32) throw new IllegalStateException("bad challenge");
            byte[] macPub = Arrays.copyOfRange(reply, 5, 37);
            String macName = Proto.utf8(Arrays.copyOfRange(reply, 37, reply.length));

            PublicKey theirs = KeyFactory.getInstance("XDH").generatePublic(
                    new X509EncodedKeySpec(Proto.concat(X25519_SPKI_PREFIX, macPub)));
            KeyAgreement agreement = KeyAgreement.getInstance("XDH");
            agreement.init(mine.getPrivate());
            agreement.doPhase(theirs, true);
            byte[] shared = agreement.generateSecret();
            byte[] salt = Proto.concat(phonePub, macPub);
            byte[] key = hkdf(shared, salt, "macindroid key v1", 32);
            byte[] codeBytes = hkdf(shared, salt, "macindroid code v1", 4);
            long number = (((codeBytes[0] & 0xffL) << 24) | ((codeBytes[1] & 0xff) << 16) | ((codeBytes[2] & 0xff) << 8)
                    | (codeBytes[3] & 0xff)) % 1_000_000;
            String code = String.format("%06d", number);
            main.post(() -> listener.onCode(code.substring(0, 3) + " " + code.substring(3), macName));

            byte[] confirm = Proto.readFrame(in);
            if (Proto.isPairing(confirm)) {
                finish(false, "Pairing was declined on the Mac.");
                return;
            }
            SecretKey secret = new SecretKeySpec(key, "AES");
            byte[] plain = Proto.open(secret, confirm); // throws if the Mac derived a different key
            if (plain[0] != Proto.HELLO) throw new IllegalStateException("unexpected first message");
            Config.savePairing(context, key, target.getAddress().getHostAddress(), macName);
            finish(true, "Paired with " + macName);
        } catch (Exception e) {
            finish(false, socket != null && socket.isClosed() ? "Cancelled." : "Couldn't pair: " + e.getMessage());
        }
    }

    private void finish(boolean ok, String message) {
        main.post(() -> listener.onFinished(ok, message));
    }

    /** HKDF-SHA256 (RFC 5869), matching CryptoKit's hkdfDerivedSymmetricKey. */
    static byte[] hkdf(byte[] ikm, byte[] salt, String info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int at = 0;
        for (int i = 1; at < length; i++) {
            mac.update(block);
            mac.update(Proto.utf8(info));
            mac.update((byte) i);
            block = mac.doFinal();
            int n = Math.min(block.length, length - at);
            System.arraycopy(block, 0, out, at, n);
            at += n;
        }
        return out;
    }
}
