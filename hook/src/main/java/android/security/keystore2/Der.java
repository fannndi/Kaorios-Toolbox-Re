package android.security.keystore2;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Date;

final class Der {

    private Der() {
    }

    static byte[] concat(byte[]... items) {
        int size = 0;
        for (byte[] item : items) {
            size += item.length;
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] item : items) {
            System.arraycopy(item, 0, result, offset, item.length);
            offset += item.length;
        }
        return result;
    }

    static byte[] tlv(int tagByte, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tagByte);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length <= 0xFF) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... items) {
        return tlv(0x30, concat(items));
    }

    static byte[] set(byte[]... items) {
        return tlv(0x31, concat(items));
    }

    static byte[] explicit(int tag, byte[] content) {
        if (tag < 0x1F) {
            return tlv(0xA0 | tag, content);
        }
        ByteArrayOutputStream tagBytes = new ByteArrayOutputStream();
        tagBytes.write(0xBF);
        int value = tag;
        int shift = 28;
        boolean started = false;
        while (shift >= 0) {
            int chunk = (value >> shift) & 0x7F;
            if (chunk != 0 || started || shift == 0) {
                started = true;
                tagBytes.write(chunk | (shift == 0 ? 0 : 0x80));
            }
            shift -= 7;
        }
        return concat(tagBytes.toByteArray(), lengthPrefix(content.length), content);
    }

    private static byte[] slice(byte[] data, int from) {
        byte[] result = new byte[data.length - from];
        System.arraycopy(data, from, result, 0, result.length);
        return result;
    }

    private static byte[] lengthPrefix(int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (length < 0x80) {
            out.write(length);
        } else if (length <= 0xFF) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
        return out.toByteArray();
    }

    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    static byte[] enumerated(long value) {
        return tlv(0x0A, BigInteger.valueOf(value).toByteArray());
    }

    static byte[] octetString(byte[] data) {
        return tlv(0x04, data);
    }

    static byte[] bitString(byte[] data) {
        return tlv(0x03, concat(new byte[]{0x00}, data));
    }

    static byte[] booleanValue(boolean value) {
        return tlv(0x01, new byte[]{(byte) (value ? 0xFF : 0x00)});
    }

    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // The first subidentifier is 40 * arc0 + arc1 encoded as a base-128 value,
        // so it needs the same varint form as the later arcs. Writing it as a single
        // byte silently truncates anything at or above 128: "2.999" encodes as 1079
        // and would be written as 55, producing a different, wrong OID.
        writeBase128(out, Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            writeBase128(out, Long.parseLong(parts[i]));
        }
        return tlv(0x06, out.toByteArray());
    }

    /** Minimal base-128 encoding, high bit set on every octet but the last. */
    private static void writeBase128(ByteArrayOutputStream out, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("OID arc must not be negative: " + value);
        }
        int shift = 0;
        while ((value >>> (shift + 7)) != 0) {
            shift += 7;
        }
        for (; shift >= 0; shift -= 7) {
            int chunk = (int) ((value >>> shift) & 0x7F);
            out.write(chunk | (shift == 0 ? 0 : 0x80));
        }
    }

    static byte[] utcTime(long millis) {
        java.util.Calendar calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        calendar.setTime(new Date(millis));
        String text = String.format(java.util.Locale.US, "%02d%02d%02d%02d%02d%02dZ",
                calendar.get(java.util.Calendar.YEAR) % 100,
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE),
                calendar.get(java.util.Calendar.SECOND));
        byte[] ascii = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            ascii[i] = (byte) text.charAt(i);
        }
        return tlv(0x17, ascii);
    }

    static byte[] raw(byte[] encoded) {
        return encoded;
    }
}
