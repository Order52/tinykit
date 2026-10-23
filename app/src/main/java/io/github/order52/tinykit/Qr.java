package io.github.order52.tinykit;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder: byte mode, versions 1 to 10, error correction level M (or L when
 * the text needs more room). Enough for Wi-Fi codes without pulling in a library.
 * Follows the structure of Project Nayuki's QR Code generator (MIT).
 */
final class Qr {

    // Indexed by [level][version]; level 0 is L, 1 is M.
    private static final int[][] ECC_PER_BLOCK = {
            {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18},
            {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26}};
    private static final int[][] BLOCKS = {
            {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4},
            {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5}};
    private static final int[] FORMAT_LEVEL = {1, 0};

    final int size;
    private final boolean[][] dark;
    private final boolean[][] function;

    private Qr(int version, int level, byte[] data) {
        size = version * 4 + 17;
        dark = new boolean[size][size];
        function = new boolean[size][size];
        drawFunctionPatterns(version, level);
        drawCodewords(addEcc(version, level, data));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormat(level, mask);
            long penalty = penalty();
            if (penalty < bestPenalty) {
                best = mask;
                bestPenalty = penalty;
            }
            applyMask(mask);
        }
        applyMask(best);
        drawFormat(level, best);
    }

    /** Returns null when the text is too long for version 10. */
    static Qr encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int level = 1; level >= 0; level--) {
            for (int version = 1; version <= 10; version++) {
                int capacity = dataCodewords(version, level) * 8;
                int countBits = version <= 9 ? 8 : 16;
                if (4 + countBits + bytes.length * 8 > capacity) continue;
                Bits bits = new Bits(capacity);
                bits.add(4, 4);
                bits.add(bytes.length, countBits);
                for (byte b : bytes) bits.add(b & 0xFF, 8);
                bits.add(0, Math.min(4, capacity - bits.length));
                bits.add(0, (8 - bits.length % 8) % 8);
                for (int pad = 0xEC; bits.length < capacity; pad ^= 0xEC ^ 0x11) bits.add(pad, 8);
                return new Qr(version, level, bits.bytes);
            }
        }
        return null;
    }

    boolean get(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size && dark[y][x];
    }

    // ---- Layout ---------------------------------------------------------------------------

    private void drawFunctionPatterns(int version, int level) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = alignmentPositions(version);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        set(align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                    }
                }
            }
        }
        drawFormat(level, 0);
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3;
                int b = i / 3;
                set(a, b, bit);
                set(b, a, bit);
            }
        }
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawFormat(int level, int mask) {
        int data = FORMAT_LEVEL[level] << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (function[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0;
                }
                dark[y][x] ^= invert;
            }
        }
    }

    /** A simplified score (runs, 2x2 blocks, balance); any mask scans, this just picks a clean one. */
    private long penalty() {
        long result = 0;
        int darkCount = 0;
        for (int a = 0; a < size; a++) {
            int runRow = 1, runCol = 1;
            for (int b = 1; b < size; b++) {
                runRow = dark[a][b] == dark[a][b - 1] ? runRow + 1 : 1;
                runCol = dark[b][a] == dark[b - 1][a] ? runCol + 1 : 1;
                if (runRow == 5) result += 3;
                else if (runRow > 5) result++;
                if (runCol == 5) result += 3;
                else if (runCol > 5) result++;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (dark[y][x]) darkCount++;
                if (x > 0 && y > 0 && dark[y][x] == dark[y][x - 1] && dark[y][x] == dark[y - 1][x]
                        && dark[y][x] == dark[y - 1][x - 1]) result += 3;
            }
        }
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return result + Math.max(k, 0) * 10L;
    }

    private void set(int x, int y, boolean value) {
        dark[y][x] = value;
        function[y][x] = true;
    }

    // ---- Error correction -------------------------------------------------------------------

    private static byte[] addEcc(int version, int level, byte[] data) {
        int blocks = BLOCKS[level][version];
        int eccLen = ECC_PER_BLOCK[level][version];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks;
        int shortLen = raw / blocks;
        byte[] divisor = divisor(eccLen);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int dataLen = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(data, k, block, 0, dataLen);
            k += dataLen;
            byte[] ecc = remainder(block, dataLen, divisor);
            // Short blocks keep an unused slot so all blocks line up when interleaving.
            int eccStart = shortLen + 1 - eccLen;
            System.arraycopy(ecc, 0, block, eccStart, eccLen);
            all[i] = block;
        }
        byte[] result = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - eccLen || j >= shortBlocks) result[n++] = all[j][i];
            }
        }
        return result;
    }

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, int length, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (int d = 0; d < length; d++) {
            int factor = (data[d] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static int[] alignmentPositions(int version) {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] result = new int[n];
        result[0] = 6;
        for (int i = n - 1, pos = version * 4 + 10; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private static int rawModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int n = version / 7 + 2;
            result -= (25 * n - 10) * n - 55;
            if (version >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int version, int level) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[level][version] * BLOCKS[level][version];
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    private static final class Bits {
        final byte[] bytes;
        int length;

        Bits(int capacity) {
            bytes = new byte[capacity / 8];
        }

        void add(int value, int count) {
            for (int i = count - 1; i >= 0; i--, length++) {
                if (((value >>> i) & 1) != 0) bytes[length >>> 3] |= (byte) (0x80 >>> (length & 7));
            }
        }
    }
}
