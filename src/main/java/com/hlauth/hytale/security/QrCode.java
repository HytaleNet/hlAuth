package com.hlauth.hytale.security;

/**
 * Minimal QR encoder (byte mode, ECC level M, versions 1–10) for otpauth URIs.
 * Output is a module matrix: {@code true} = dark.
 */
public final class QrCode {

    private static final int[][] ECC_M = {
        // version, dataCw, ecPerBlock, g1Blocks, g1Data, g2Blocks, g2Data
        {1, 16, 10, 1, 16, 0, 0},
        {2, 28, 16, 1, 28, 0, 0},
        {3, 44, 26, 1, 44, 0, 0},
        {4, 64, 18, 2, 32, 0, 0},
        {5, 86, 24, 2, 43, 0, 0},
        {6, 108, 16, 4, 27, 0, 0},
        {7, 124, 18, 4, 31, 0, 0},
        {8, 154, 22, 2, 38, 2, 39},
        {9, 182, 22, 3, 36, 2, 37},
        {10, 216, 26, 4, 43, 1, 44}
    };

    private static final int[][] ALIGN = {
        {},
        {6, 18},
        {6, 22},
        {6, 26},
        {6, 30},
        {6, 34},
        {6, 22, 38},
        {6, 24, 42},
        {6, 26, 46},
        {6, 28, 50}
    };

    private static final int[] VERSION_BITS = {
        0, 0, 0, 0, 0, 0, 0,
        0x07C94, 0x08EBA, 0x09A27, 0x0A4D4
    };

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if (x >= 256) {
                x ^= 0x11D;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    private QrCode() {
    }

    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int version = chooseVersion(data.length);
        byte[] codewords = encodeData(data, version);
        byte[] interleaved = interleave(codewords, version);
        return draw(interleaved, version);
    }

    /** Half-block Unicode QR (two modules per line) plus a quiet zone. */
    public static String toHalfBlocks(boolean[][] modules) {
        int n = modules.length;
        int q = 4;
        int size = n + q * 2;
        boolean[][] padded = new boolean[size][size];
        for (int y = 0; y < n; y++) {
            System.arraycopy(modules[y], 0, padded[y + q], q, n);
        }
        StringBuilder sb = new StringBuilder((size / 2 + 1) * (size + 1));
        for (int y = 0; y < size; y += 2) {
            for (int x = 0; x < size; x++) {
                boolean top = padded[y][x];
                boolean bot = y + 1 < size && padded[y + 1][x];
                if (top && bot) {
                    sb.append('█');
                } else if (top) {
                    sb.append('▀');
                } else if (bot) {
                    sb.append('▄');
                } else {
                    sb.append(' ');
                }
            }
            if (y + 2 < size) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Inline Custom UI markup: black modules absolutely placed on a white square.
     * Same approach as mauth — flow layout (Top/Left rows) leaves gaps and tears
     * the QR; {@code Anchor: (Top, Left, Width, Height)} does not.
     */
    public static String toUiMarkup(boolean[][] modules) {
        return toUiMarkup(modules, 6, 4);
    }

    public static String toUiMarkup(boolean[][] modules, int modulePx, int quietZone) {
        if (modules == null || modules.length == 0) {
            return "";
        }
        int n = modules.length;
        int q = Math.max(0, quietZone);
        int size = n + q * 2;
        int cell = Math.max(1, modulePx);
        int px = size * cell;
        // 1px overlap so adjacent modules never show a hairline gap
        int draw = cell + 1;
        StringBuilder sb = new StringBuilder(size * size * 48);
        sb.append("Group { LayoutMode: Full; Background: #ffffff; Anchor: (Width: ")
            .append(px).append(", Height: ").append(px).append(");\n");
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!isPaddedDark(modules, n, q, x, y)) {
                    continue;
                }
                sb.append("Group { Anchor: (Top: ").append(y * cell)
                    .append(", Left: ").append(x * cell)
                    .append(", Width: ").append(draw)
                    .append(", Height: ").append(draw)
                    .append("); Background: #000000; }\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isPaddedDark(boolean[][] modules, int n, int q, int x, int y) {
        int mx = x - q;
        int my = y - q;
        return mx >= 0 && my >= 0 && mx < n && my < n && modules[my][mx];
    }

    private static int chooseVersion(int byteLen) {
        for (int[] row : ECC_M) {
            int version = row[0];
            int dataCw = row[1];
            int countBits = version < 10 ? 8 : 16;
            int bits = 4 + countBits + byteLen * 8 + 4;
            int capacity = dataCw * 8;
            if (bits <= capacity) {
                return version;
            }
        }
        throw new IllegalArgumentException("TOTP URI is too long for a QR code");
    }

    private static byte[] encodeData(byte[] data, int version) {
        int dataCw = ECC_M[version - 1][1];
        int countBits = version < 10 ? 8 : 16;
        BitBuffer bits = new BitBuffer();
        bits.append(0b0100, 4);
        bits.append(data.length, countBits);
        for (byte b : data) {
            bits.append(b & 0xFF, 8);
        }
        int capacity = dataCw * 8;
        int remain = capacity - bits.size();
        bits.append(0, Math.min(4, remain));
        while (bits.size() % 8 != 0) {
            bits.append(0, 1);
        }
        boolean padEc = true;
        while (bits.size() < capacity) {
            bits.append(padEc ? 0xEC : 0x11, 8);
            padEc = !padEc;
        }
        byte[] out = new byte[dataCw];
        for (int i = 0; i < dataCw; i++) {
            out[i] = (byte) bits.getByte(i);
        }
        return out;
    }

    private static byte[] interleave(byte[] data, int version) {
        int[] row = ECC_M[version - 1];
        int ecPer = row[2];
        int g1 = row[3];
        int g1Data = row[4];
        int g2 = row[5];
        int g2Data = row[6];
        int blocks = g1 + g2;
        byte[][] dataBlocks = new byte[blocks][];
        byte[][] ecBlocks = new byte[blocks][];
        int offset = 0;
        for (int i = 0; i < g1; i++) {
            dataBlocks[i] = java.util.Arrays.copyOfRange(data, offset, offset + g1Data);
            ecBlocks[i] = reedSolomon(dataBlocks[i], ecPer);
            offset += g1Data;
        }
        for (int i = 0; i < g2; i++) {
            int idx = g1 + i;
            dataBlocks[idx] = java.util.Arrays.copyOfRange(data, offset, offset + g2Data);
            ecBlocks[idx] = reedSolomon(dataBlocks[idx], ecPer);
            offset += g2Data;
        }
        int maxData = Math.max(g1Data, g2Data);
        BitBuffer bits = new BitBuffer();
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < dataBlocks[b].length) {
                    bits.append(dataBlocks[b][i] & 0xFF, 8);
                }
            }
        }
        for (int i = 0; i < ecPer; i++) {
            for (int b = 0; b < blocks; b++) {
                bits.append(ecBlocks[b][i] & 0xFF, 8);
            }
        }
        int remainder = version >= 2 && version <= 6 ? 7 : 0;
        bits.append(0, remainder);
        int totalBytes = (bits.size() + 7) / 8;
        byte[] out = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++) {
            out[i] = (byte) bits.getByte(i);
        }
        return out;
    }

    private static byte[] reedSolomon(byte[] data, int ecCount) {
        int[] gen = generator(ecCount);
        int[] result = new int[ecCount];
        for (byte value : data) {
            int factor = (value & 0xFF) ^ result[0];
            System.arraycopy(result, 1, result, 0, ecCount - 1);
            result[ecCount - 1] = 0;
            if (factor != 0) {
                for (int i = 0; i < ecCount; i++) {
                    result[i] ^= mul(gen[i], factor);
                }
            }
        }
        byte[] out = new byte[ecCount];
        for (int i = 0; i < ecCount; i++) {
            out[i] = (byte) result[i];
        }
        return out;
    }

    private static int[] generator(int degree) {
        int[] poly = {1};
        for (int i = 0; i < degree; i++) {
            int[] next = new int[poly.length + 1];
            for (int j = 0; j < poly.length; j++) {
                next[j] ^= poly[j];
                next[j + 1] ^= mul(poly[j], EXP[i]);
            }
            poly = next;
        }
        // poly is [1, ..., a^...] of length degree+1; skip leading 1 for remainder loop
        int[] gen = new int[degree];
        System.arraycopy(poly, 1, gen, 0, degree);
        return gen;
    }

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[LOG[a] + LOG[b]];
    }

    private static boolean[][] draw(byte[] data, int version) {
        int size = 21 + (version - 1) * 4;
        boolean[][] modules = new boolean[size][size];
        boolean[][] reserved = new boolean[size][size];
        placeFinders(modules, reserved, size);
        placeTiming(modules, reserved, size);
        placeAlignments(modules, reserved, version);
        placeDarkModule(modules, reserved, version);
        reserveFormat(reserved, size);
        if (version >= 7) {
            placeVersion(modules, reserved, version);
        }
        placeData(modules, reserved, data, size);
        int mask = pickMask(modules, reserved, size);
        applyMask(modules, reserved, mask, size);
        placeFormat(modules, mask, size);
        return modules;
    }

    private static void placeFinders(boolean[][] m, boolean[][] r, int size) {
        placeFinder(m, r, 0, 0);
        placeFinder(m, r, size - 7, 0);
        placeFinder(m, r, 0, size - 7);
        // separators
        for (int i = 0; i < 8; i++) {
            reserveBlank(m, r, 7, i);
            reserveBlank(m, r, i, 7);
            reserveBlank(m, r, size - 8, i);
            reserveBlank(m, r, size - 8 + i, 7);
            reserveBlank(m, r, i, size - 8);
            reserveBlank(m, r, 7, size - 8 + i);
        }
    }

    private static void placeFinder(boolean[][] m, boolean[][] r, int x, int y) {
        for (int dy = 0; dy < 7; dy++) {
            for (int dx = 0; dx < 7; dx++) {
                boolean dark = dx == 0 || dx == 6 || dy == 0 || dy == 6
                    || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4);
                m[y + dy][x + dx] = dark;
                r[y + dy][x + dx] = true;
            }
        }
    }

    private static void reserveBlank(boolean[][] m, boolean[][] r, int x, int y) {
        if (x < 0 || y < 0 || x >= m.length || y >= m.length) {
            return;
        }
        m[y][x] = false;
        r[y][x] = true;
    }

    private static void placeTiming(boolean[][] m, boolean[][] r, int size) {
        for (int i = 8; i < size - 8; i++) {
            boolean dark = i % 2 == 0;
            if (!r[6][i]) {
                m[6][i] = dark;
                r[6][i] = true;
            }
            if (!r[i][6]) {
                m[i][6] = dark;
                r[i][6] = true;
            }
        }
    }

    private static void placeAlignments(boolean[][] m, boolean[][] r, int version) {
        if (version < 2) {
            return;
        }
        int[] pos = ALIGN[version - 1];
        int size = m.length;
        for (int y : pos) {
            for (int x : pos) {
                // Skip the three finder corners; do not skip merely because
                // the center sits on a timing pattern (x=6 or y=6).
                if ((x <= 8 && y <= 8)
                        || (x >= size - 9 && y <= 8)
                        || (x <= 8 && y >= size - 9)) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        boolean dark = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                        m[y + dy][x + dx] = dark;
                        r[y + dy][x + dx] = true;
                    }
                }
            }
        }
    }

    private static void placeDarkModule(boolean[][] m, boolean[][] r, int version) {
        int y = 4 * version + 9;
        m[y][8] = true;
        r[y][8] = true;
    }

    private static void reserveFormat(boolean[][] r, int size) {
        for (int i = 0; i < 9; i++) {
            r[8][i] = true;
            r[i][8] = true;
        }
        for (int i = 0; i < 8; i++) {
            r[8][size - 8 + i] = true;
            r[size - 8 + i][8] = true;
        }
    }

    private static void placeVersion(boolean[][] m, boolean[][] r, int version) {
        int bits = VERSION_BITS[version];
        int size = m.length;
        for (int i = 0; i < 18; i++) {
            boolean dark = ((bits >> i) & 1) != 0;
            int a = i / 3;
            int b = i % 3;
            m[size - 11 + b][a] = dark;
            r[size - 11 + b][a] = true;
            m[a][size - 11 + b] = dark;
            r[a][size - 11 + b] = true;
        }
    }

    private static void placeData(boolean[][] m, boolean[][] r, byte[] data, int size) {
        int bit = 0;
        int total = data.length * 8;
        int dir = -1;
        for (int x = size - 1; x > 0; x -= 2) {
            if (x == 6) {
                x--;
            }
            for (int i = 0; i < size; i++) {
                int y = dir < 0 ? size - 1 - i : i;
                for (int dx = 0; dx < 2; dx++) {
                    int xx = x - dx;
                    if (r[y][xx]) {
                        continue;
                    }
                    boolean dark = false;
                    if (bit < total) {
                        int b = data[bit / 8] & 0xFF;
                        dark = ((b >> (7 - (bit % 8))) & 1) != 0;
                    }
                    m[y][xx] = dark;
                    bit++;
                }
            }
            dir = -dir;
        }
    }

    private static int pickMask(boolean[][] modules, boolean[][] reserved, int size) {
        int best = 0;
        int bestScore = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(modules, reserved, mask, size);
            placeFormat(modules, mask, size);
            int score = penalty(modules, size);
            applyMask(modules, reserved, mask, size); // toggle back
            if (score < bestScore) {
                bestScore = score;
                best = mask;
            }
        }
        return best;
    }

    private static void applyMask(boolean[][] m, boolean[][] r, int mask, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (r[y][x]) {
                    continue;
                }
                if (maskBit(mask, y, x)) {
                    m[y][x] = !m[y][x];
                }
            }
        }
    }

    private static boolean maskBit(int mask, int y, int x) {
        return switch (mask) {
            case 0 -> (y + x) % 2 == 0;
            case 1 -> y % 2 == 0;
            case 2 -> x % 3 == 0;
            case 3 -> (y + x) % 3 == 0;
            case 4 -> (y / 2 + x / 3) % 2 == 0;
            case 5 -> (y * x) % 2 + (y * x) % 3 == 0;
            case 6 -> ((y * x) % 2 + (y * x) % 3) % 2 == 0;
            default -> ((y + x) % 2 + (y * x) % 3) % 2 == 0;
        };
    }

    private static void placeFormat(boolean[][] m, int mask, int size) {
        int bits = formatBits(mask);
        for (int i = 0; i < 15; i++) {
            boolean dark = ((bits >> i) & 1) != 0;
            // around top-left
            if (i < 6) {
                m[i][8] = dark;
            } else if (i < 8) {
                m[i + 1][8] = dark;
            } else if (i == 8) {
                m[8][7] = dark;
            } else {
                m[8][14 - i] = dark;
            }
            // split copy
            if (i < 8) {
                m[8][size - 1 - i] = dark;
            } else {
                m[size - 15 + i][8] = dark;
            }
        }
        m[size - 8][8] = true;
    }

    private static int formatBits(int mask) {
        int data = (0b00 << 3) | mask; // ECC M = 00
        int d = data << 10;
        int gen = 0x537;
        for (int i = 14; i >= 10; i--) {
            if (((d >> i) & 1) != 0) {
                d ^= gen << (i - 10);
            }
        }
        return ((data << 10) | d) ^ 0x5412;
    }

    private static int penalty(boolean[][] m, int size) {
        int n1 = 0;
        int n2 = 0;
        int n3 = 0;
        int dark = 0;
        for (int y = 0; y < size; y++) {
            int run = 1;
            for (int x = 1; x <= size; x++) {
                boolean same = x < size && m[y][x] == m[y][x - 1];
                if (same) {
                    run++;
                } else {
                    if (run >= 5) {
                        n1 += 3 + (run - 5);
                    }
                    run = 1;
                }
            }
            run = 1;
            for (int x = 1; x <= size; x++) {
                boolean same = x < size && m[x][y] == m[x - 1][y];
                if (same) {
                    run++;
                } else {
                    if (run >= 5) {
                        n1 += 3 + (run - 5);
                    }
                    run = 1;
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean v = m[y][x];
                if (v == m[y][x + 1] && v == m[y + 1][x] && v == m[y + 1][x + 1]) {
                    n2 += 3;
                }
            }
        }
        for (int y = 0; y < size; y++) {
            n3 += finderPenaltyRow(m[y]);
            boolean[] col = new boolean[size];
            for (int x = 0; x < size; x++) {
                col[x] = m[x][y];
                if (m[y][x]) {
                    dark++;
                }
            }
            n3 += finderPenaltyRow(col);
        }
        int total = size * size;
        int percent = (dark * 100) / total;
        int k = Math.abs(percent - 50) / 5;
        int n4 = k * 10;
        return n1 + n2 + n3 + n4;
    }

    private static int finderPenaltyRow(boolean[] row) {
        int score = 0;
        int n = row.length;
        for (int i = 0; i <= n - 7; i++) {
            if (row[i] && !row[i + 1] && row[i + 2] && row[i + 3] && row[i + 4]
                    && !row[i + 5] && row[i + 6]) {
                boolean left = i >= 4 && !row[i - 1] && !row[i - 2] && !row[i - 3] && !row[i - 4];
                boolean right = i + 10 < n && !row[i + 7] && !row[i + 8] && !row[i + 9] && !row[i + 10];
                if (left || right) {
                    score += 40;
                }
            }
        }
        return score;
    }

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        private int size;

        void append(int value, int length) {
            for (int i = length - 1; i >= 0; i--) {
                if (((value >> i) & 1) != 0) {
                    bits.set(size);
                }
                size++;
            }
        }

        int size() {
            return size;
        }

        int getByte(int index) {
            int v = 0;
            for (int i = 0; i < 8; i++) {
                if (bits.get(index * 8 + i)) {
                    v |= 1 << (7 - i);
                }
            }
            return v;
        }
    }
}
