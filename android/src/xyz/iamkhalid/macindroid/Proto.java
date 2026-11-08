package xyz.iamkhalid.macindroid;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Wire format shared with the Mac (mac/Sources/Link.swift): frames are a 4-byte big-endian length, then an
 * AES-256-GCM sealed box (12-byte nonce | ciphertext | 16-byte tag) whose plaintext is 1 kind byte + payload.
 * Before pairing, plaintext frames starting with "MDP1" carry the key exchange (see Pairing).
 */
final class Proto {
    static final byte TEXT = 1, IMAGE = 2, HELLO = 3, PING = 4, PONG = 5;
    static final byte NOTIF_POST = 10, NOTIF_REMOVE = 11, NOTIF_REPLY = 12, NOTIF_ACTION = 13, NOTIF_DISMISS = 14, NOTIF_OPEN = 15;
    static final byte FILE_OFFER = 20, FILE_CHUNK = 21, FILE_END = 22, FILE_CANCEL = 23, LINK = 24;
    static final byte RING = 30, RING_STOP = 31, STATUS = 32, MIRROR_PREP = 33, MIRROR_READY = 34, MIRROR_DONE = 35;
    static final byte PHOTO = 36, PHOTO_GET = 37, PHOTO_FULL = 38;
    /** A one-time code found in a phone notification: JSON {code, from, app, key}. Never logged. */
    static final byte CODE = 40;

    static final int PORT = 47800;
    static final String SERVICE_TYPE = "_macindroid._tcp";
    static final int MAX_FRAME = 32 * 1024 * 1024;
    static final int CHUNK = 256 * 1024;
    static final byte[] PAIR_MAGIC = {'M', 'D', 'P', '1'};
    static final byte PAIR_REQUEST = 1, PAIR_CHALLENGE = 2, PAIR_CLOSED = 3, PAIR_DECLINED = 4;

    private static final SecureRandom RNG = new SecureRandom();

    private Proto() {}

    static byte[] seal(SecretKey key, byte kind, byte[] payload) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        RNG.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] out = new byte[12 + cipher.getOutputSize(payload.length + 1)];
        System.arraycopy(nonce, 0, out, 0, 12);
        int n = cipher.update(new byte[] {kind}, 0, 1, out, 12);
        n += cipher.doFinal(payload, 0, payload.length, out, 12 + n);
        return n + 12 == out.length ? out : Arrays.copyOf(out, n + 12);
    }

    /** Returns the kind byte followed by the payload. */
    static byte[] open(SecretKey key, byte[] frame) throws GeneralSecurityException {
        if (frame.length < 12 + 16 + 1) throw new GeneralSecurityException("frame too short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, frame, 0, 12));
        return cipher.doFinal(frame, 12, frame.length - 12);
    }

    static void writeFrame(DataOutputStream out, byte[] body) throws IOException {
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }

    static byte[] readFrame(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n <= 0 || n > MAX_FRAME) throw new IOException("bad frame size " + n);
        byte[] frame = new byte[n];
        in.readFully(frame);
        return frame;
    }

    static boolean isPairing(byte[] frame) {
        return frame.length > 4 && frame[0] == 'M' && frame[1] == 'D' && frame[2] == 'P' && frame[3] == '1';
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    static byte[] id(long id) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) id;
            id >>>= 8;
        }
        return b;
    }

    static long readId(byte[] payload) {
        long id = 0;
        for (int i = 0; i < 8; i++) id = (id << 8) | (payload[i] & 0xff);
        return id;
    }

    static long randomId() {
        long id;
        do id = RNG.nextLong(); while (id == 0);
        return id;
    }

    static String digest(byte kind, byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(kind);
            md.update(payload);
            return Arrays.toString(md.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String imageMime(byte[] d) {
        if (d.length > 3 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length > 2 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return "image/jpeg";
        if (d.length > 3 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "image/gif";
        if (d.length > 11 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        return "image/png";
    }

    static String extension(String mime) {
        switch (mime) {
            case "image/jpeg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            default: return "png";
        }
    }
}
