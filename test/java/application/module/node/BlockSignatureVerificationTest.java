package application.module.node;

import application.module.node.common.TestConstants;
import application.module.node.crypto.Crypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extremely thorough block signature verification test.
 * Uses EXACT hex data from mainnet debug dump for:
 *   - Block 59763 (id=6603831533630817502) — the FAILING block
 *   - Block 59762 (id=14541418275589294019) — the PREVIOUS working block
 */
class BlockSignatureVerificationTest {

    // BLOCK 59763 (FAILING) — from blocksigdbg dump 20260904-201331
    static final String B59763_DATA2_HEX =
            "03000000dcefdc00c387dfbee07ecdc9" +
            "03000000" +
            "a506c87d0a000000" +
            "006ac06100000000" +
            "28020000" +
            "92e20334da3e7f5e814277505dbd4c96cf7f588c8721277cb44901a1f30724fb" +
            "b516bf74e3024931cca3408efa917876" +
            "7a05cf81207a58a788d04409af5c6a67" +
            "8e854b78905525512b08683b73b0161c" +
            "a584f66a00bbfe020626013c20f5fa37" +
            "c387dfbee07ecdc97260802d8619317a" +
            "12c0cfeed640709092ee5ced69a418bd" +
            "5274110000000000";

    static final String B59763_SIG_HEX =
            "dadf19b14fa43fc4f35943a5fa61761b" +
            "13083fdfe095dca8c101e356c62e790f" +
            "9838d72507a6afe5f181637c763c274d" +
            "72093cfee8ff9fa60c34962fb9566600";

    static final String B59763_PK_HEX =
            "b516bf74e3024931cca3408efa917876" +
            "7a05cf81207a58a788d04409af5c6a67";

    // BLOCK 59762 (WORKING) — from blocksigdbg dump 20260904-201331
    static final String B59762_DATA2_HEX =
            "03000000c5eddc009b2fa8caa263dd40" +
            "03000000" +
            "943c8fc07b000000" +
            "00a3e11100000000" +
            "10020000" +
            "a7e1f276dc743e13b65aa043a88da667" +
            "d3269ecdc8db19077ce5995aeb837bdc" +
            "3c8dfe769ba22aa94efb3cefa22129d3" +
            "937dc4d0798577efea4be91729dc6467" +
            "18d71f76d1b23737db0f65c6b5d63e97" +
            "231362091baadbe2ee9402d40d4b0a30" +
            "9b2fa8caa263dd4022386ea4573e688b" +
            "ee8e7c635a736f04a3d03bed663c882c" +
            "0d48680000000000";

    static final String B59762_SIG_HEX =
            "7bac7f72c1e8ca474b8e20e81087a90a" +
            "5bc5ff3ce48e054cb44a71599a4ef607" +
            "f31841f1d0c0b497277f5d87d474c1c1" +
            "76e1a0ad7db41a9c0d841ec64aaecb39";

    static final String B59762_PK_HEX =
            "3c8dfe769ba22aa94efb3cefa22129d3" +
            "937dc4d0798577efea4be91729dc6467";

    static final long B59762_ID = -3905325798120257597L; // unsigned: 14541418275589294019
    static final long B59763_ID = 6603831533630817502L;

    // --- Helper: hex → bytes ---
    static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static class ParsedBlock {
        int version, timestamp, txCount, payloadLength;
        long previousBlockId, totalAmountNqt, totalFeeNqt, nonce;
        byte[] payloadHash, generatorPublicKey, generationSignature, previousBlockHash;

        @Override public String toString() {
            return String.format("v=%d ts=%d(0x%08X) prevId=%d(0x%016X) tx=%d amt=%d fee=%d "
                    + "pLen=%d nonce=%d pk=%s prevHash=%s",
                    version, timestamp, timestamp, previousBlockId, previousBlockId,
                    txCount, totalAmountNqt, totalFeeNqt, payloadLength, nonce,
                    toHex(generatorPublicKey), toHex(previousBlockHash));
        }
    }

    static ParsedBlock parseUnsigned(byte[] d2) {
        ByteBuffer buf = ByteBuffer.wrap(d2).order(ByteOrder.LITTLE_ENDIAN);
        ParsedBlock p = new ParsedBlock();
        p.version = buf.getInt();
        p.timestamp = buf.getInt();
        p.previousBlockId = buf.getLong();
        p.txCount = buf.getInt();
        if (p.version >= 3) { p.totalAmountNqt = buf.getLong(); p.totalFeeNqt = buf.getLong(); }
        else { p.totalAmountNqt = buf.getInt(); p.totalFeeNqt = buf.getInt(); }
        p.payloadLength = buf.getInt();
        p.payloadHash = new byte[32]; buf.get(p.payloadHash);
        p.generatorPublicKey = new byte[32]; buf.get(p.generatorPublicKey);
        p.generationSignature = new byte[32]; buf.get(p.generationSignature);
        if (p.version > 1) { p.previousBlockHash = new byte[32]; buf.get(p.previousBlockHash); }
        p.nonce = buf.getLong();
        return p;
    }

    static byte[] reconstructUnsigned(ParsedBlock p) {
        int sz = 4+4+8+4+(p.version < 3 ? 8 : 16)+4+32+32+32+32+8;
        ByteBuffer buf = ByteBuffer.allocate(sz).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(p.version); buf.putInt(p.timestamp); buf.putLong(p.previousBlockId);
        buf.putInt(p.txCount);
        if (p.version < 3) { buf.putInt((int)p.totalAmountNqt); buf.putInt((int)p.totalFeeNqt); }
        else { buf.putLong(p.totalAmountNqt); buf.putLong(p.totalFeeNqt); }
        buf.putInt(p.payloadLength);
        buf.put(p.payloadHash); buf.put(p.generatorPublicKey); buf.put(p.generationSignature);
        if (p.version > 1) buf.put(p.previousBlockHash);
        buf.putLong(p.nonce);
        return buf.array();
    }

    static String verifyBC(byte[] data, byte[] sig, byte[] pk) {
        try {
            var params = new org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pk, 0);
            var signer = new org.bouncycastle.crypto.signers.Ed25519Signer();
            signer.init(false, params);
            signer.update(data, 0, data.length);
            return signer.verifySignature(sig) ? "VALID" : "INVALID";
        } catch (Exception e) {
            return "EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Full report for one block: parse, reconstruct, verify, ID check. */
    static String report(String label, String d2Hex, String sigHex, String pkHex,
                         long expectedId, int expectedHeight) {
        StringBuilder r = new StringBuilder();
        byte[] d2 = hexToBytes(d2Hex), sig = hexToBytes(sigHex), pk = hexToBytes(pkHex);
        r.append("========== ").append(label).append(" (h=").append(expectedHeight)
                .append(" id=").append(expectedId).append(") ==========\n");
        r.append(String.format("  d2.len=%d sig.len=%d pk.len=%d\n", d2.length, sig.length, pk.length));

        ParsedBlock p = parseUnsigned(d2);
        r.append("  ").append(p).append("\n");

        byte[] recon = reconstructUnsigned(p);
        boolean match = Arrays.equals(d2, recon);
        r.append("  Reconstruction: ").append(match ? "MATCH" : "MISMATCH!").append("\n");
        if (!match)
            for (int i = 0; i < Math.min(d2.length, recon.length); i++)
                if (d2[i] != recon[i]) { r.append(String.format("    byte[%d]: d2=%02x recon=%02x\n", i, d2[i], recon[i])); break; }

        boolean strict = Crypto.verify(sig, d2, pk, true);
        boolean lenient = Crypto.verify(sig, d2, pk, false);
        String bc = verifyBC(d2, sig, pk);
        r.append("  signumj(canonical=true)  = ").append(strict ? "VALID" : "INVALID").append("\n");
        r.append("  signumj(canonical=false) = ").append(lenient ? "VALID" : "INVALID").append("\n");
        r.append("  bouncyCastle             = ").append(bc).append("\n");

        // Block ID: SHA-256(full signed) → first 8 bytes LE long
        byte[] full = new byte[d2.length + sig.length];
        System.arraycopy(d2, 0, full, 0, d2.length);
        System.arraycopy(sig, 0, full, d2.length, sig.length);
        byte[] hash = Crypto.sha256().digest(full);
        long computedId = ByteBuffer.wrap(hash, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        r.append("  computedId = ").append(computedId).append("\n");
        r.append("  expectedId = ").append(expectedId).append("\n");
        r.append("  ID match   = ").append(computedId == expectedId ? "YES" : "NO!").append("\n");

        boolean pkMatch = Arrays.equals(p.generatorPublicKey, pk);
        r.append("  PK in data2 == provided PK? ").append(pkMatch ? "YES" : "NO!").append("\n");
        r.append("----------\n\n");
        return r.toString();
    }

    @Test
    void verifyBlock59762_previousBlock() {
        String rpt = report("BLOCK 59762 (previous, working)",
                B59762_DATA2_HEX, B59762_SIG_HEX, B59762_PK_HEX,
                B59762_ID, 59762);
        System.out.println(rpt);

        boolean result = Crypto.verify(hexToBytes(B59762_SIG_HEX), hexToBytes(B59762_DATA2_HEX),
                hexToBytes(B59762_PK_HEX), true);
        assertTrue(result, "Block 59762 signature MUST be valid (works in production)");
    }

    @Test
    void verifyBlock59763_failingBlock_diagnosis() {
        String rpt = report("BLOCK 59763 (failing)",
                B59763_DATA2_HEX, B59763_SIG_HEX, B59763_PK_HEX,
                B59763_ID, 59763);
        System.out.println(rpt);
        // No assertion — we want to SEE the result, not fail
    }

    @Test
    void roundTrip_provesCryptoWorks() {
        byte[] pk = Crypto.getPublicKey(TestConstants.TEST_SECRET_PHRASE);
        byte[] msg = "block signature test message".getBytes();
        byte[] sig = Crypto.sign(msg, TestConstants.TEST_SECRET_PHRASE);
        assertTrue(Crypto.verify(sig, msg, pk, true), "Round-trip MUST work");
        assertEquals("VALID", verifyBC(msg, sig, pk), "BC round-trip MUST work");
    }

    @Test
    void data2RoundTrip_bothBlocks() {
        byte[] d2a = hexToBytes(B59762_DATA2_HEX);
        assertArrayEquals(d2a, reconstructUnsigned(parseUnsigned(d2a)),
                "59762: data2 must equal parse→reconstruct");

        byte[] d2b = hexToBytes(B59763_DATA2_HEX);
        assertArrayEquals(d2b, reconstructUnsigned(parseUnsigned(d2b)),
                "59763: data2 must equal parse→reconstruct");
    }

    @TempDir static Path tempDir;

    @Test
    void dumpFullReportToFile() throws IOException {
        StringBuilder sb = new StringBuilder("=== BLOCK SIG TEST REPORT ===\n\n");
        sb.append(report("BLOCK 59762", B59762_DATA2_HEX, B59762_SIG_HEX, B59762_PK_HEX,
                B59762_ID, 59762));
        sb.append(report("BLOCK 59763", B59763_DATA2_HEX, B59763_SIG_HEX, B59763_PK_HEX,
                B59763_ID, 59763));
        byte[] pk = Crypto.getPublicKey(TestConstants.TEST_SECRET_PHRASE);
        byte[] msg = "sanity".getBytes(), sig = Crypto.sign(msg, TestConstants.TEST_SECRET_PHRASE);
        sb.append("=== SANITY ===\n  signumj=").append(Crypto.verify(sig, msg, pk, true))
                .append(" bc=").append(verifyBC(msg, sig, pk)).append("\n");
        Path out = tempDir.resolve("blocksig-test.txt");
        Files.writeString(out, sb.toString());
        System.out.println("Report: " + out.toAbsolutePath());
    }

    @Test
    void tamperedData2_shouldFail() {
        byte[] d2 = hexToBytes(B59762_DATA2_HEX);
        byte[] sig = hexToBytes(B59762_SIG_HEX);
        byte[] pk = hexToBytes(B59762_PK_HEX);
        assertTrue(Crypto.verify(sig, d2, pk, true), "Original must verify");
        byte[] tampered = d2.clone();
        tampered[5] ^= 0x01;
        System.out.println("  Tampered: " + Crypto.verify(sig, tampered, pk, true));
    }
}