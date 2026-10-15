package com.kukuqi.tvbox.engine.player.subtitle;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** OpenType name-table reader for builds whose Media3 package does not include libass. */
final class FontFamily {
    static String read(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file,"r")) {
            long font = 0;
            if (input.readInt() == 0x74746366) { input.skipBytes(8); font = Integer.toUnsignedLong(input.readInt()); }
            input.seek(font+4);
            int count = input.readUnsignedShort(); input.skipBytes(6);
            for (int i = 0; i < count; i++) {
                int tag = input.readInt(); input.readInt();
                long offset = Integer.toUnsignedLong(input.readInt()); input.readInt();
                if (tag != 0x6e616d65) continue;
                input.seek(offset); input.readUnsignedShort();
                int records = input.readUnsignedShort(), storage = input.readUnsignedShort();
                String best = ""; int score = -1;
                for (int j = 0; j < records; j++) {
                    int platform = input.readUnsignedShort(); input.readUnsignedShort();
                    int language = input.readUnsignedShort(), name = input.readUnsignedShort();
                    int length = input.readUnsignedShort(), relative = input.readUnsignedShort();
                    long next = input.getFilePointer();
                    if ((name == 1 || name == 16) && length > 0 && length <= 4096) {
                        int rank = (name == 16 ? 10 : 0)+(language == 0x409 ? 2 : 0)+(platform == 3 ? 1 : 0);
                        if (rank > score) {
                            input.seek(offset+storage+relative); byte[] bytes = new byte[length]; input.readFully(bytes);
                            String family = new String(bytes,platform == 0 || platform == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1).trim();
                            if (!family.isEmpty()) { best = family; score = rank; }
                            input.seek(next);
                        }
                    }
                }
                return best;
            }
            return "";
        }
    }
}
