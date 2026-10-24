package io.kelta.gateway.geo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a minimal IPv4 MaxMind DB (format 2.0, 24-bit records) holding one network, so
 * the geo tests decode through the real {@code com.maxmind.db.Reader} rather than a mock.
 * Values may be {@link String}, {@link Double}, {@link Integer} (uint16), {@link Long}
 * (uint32), {@link BigInteger} (uint64), {@link List} and {@link Map}.
 */
final class TestMmdbWriter {

    private static final int RECORD_SIZE = 24;
    private static final byte[] METADATA_MARKER =
            "«ÍïMaxMind.com".getBytes(StandardCharsets.ISO_8859_1);

    private TestMmdbWriter() {
    }

    /** GeoLite2-City-shaped record used across the geo tests. */
    static Map<String, Object> cityRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("country", Map.of("iso_code", "PT"));
        record.put("subdivisions", List.of(Map.of("iso_code", "11", "names", Map.of("en", "Lisbon"))));
        record.put("city", Map.of("names", Map.of("en", "Cascais")));
        record.put("location", Map.of("latitude", 38.6979, "longitude", -9.4207, "accuracy_radius", 20));
        return record;
    }

    static Path write(Path file, String networkAddress, int prefixLength, Map<String, Object> data,
            long buildEpoch) throws IOException {
        InetAddress address = InetAddress.ofLiteral(networkAddress);
        if (!(address instanceof Inet4Address)) {
            throw new IllegalArgumentException("IPv4 networks only");
        }
        byte[] ip = address.getAddress();
        int nodeCount = prefixLength;
        long dataRecord = nodeCount + 16L; // data section offset 0, past the 16-byte separator

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int node = 0; node < nodeCount; node++) {
            int bit = (ip[node / 8] >> (7 - node % 8)) & 1;
            long match = node == nodeCount - 1 ? dataRecord : node + 1;
            long empty = nodeCount; // "no data" record
            writeUint24(out, bit == 0 ? match : empty);
            writeUint24(out, bit == 0 ? empty : match);
        }
        out.write(new byte[16]);
        encode(out, data);

        out.write(METADATA_MARKER);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("binary_format_major_version", 2);
        metadata.put("binary_format_minor_version", 0);
        metadata.put("build_epoch", BigInteger.valueOf(buildEpoch));
        metadata.put("database_type", "GeoLite2-City");
        metadata.put("languages", List.of("en"));
        metadata.put("description", Map.of("en", "Kelta gateway test database"));
        metadata.put("ip_version", 4);
        metadata.put("node_count", (long) nodeCount);
        metadata.put("record_size", RECORD_SIZE);
        encode(out, metadata);

        Files.write(file, out.toByteArray());
        return file;
    }

    private static void writeUint24(ByteArrayOutputStream out, long value) {
        out.write((int) (value >> 16) & 0xFF);
        out.write((int) (value >> 8) & 0xFF);
        out.write((int) value & 0xFF);
    }

    private static void encode(ByteArrayOutputStream out, Object value) throws IOException {
        switch (value) {
            case String s -> {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                control(out, 2, bytes.length);
                out.write(bytes);
            }
            case Double d -> {
                control(out, 3, 8);
                out.write(ByteBuffer.allocate(8).putDouble(d).array());
            }
            case Integer i -> unsigned(out, 5, BigInteger.valueOf(i));
            case Long l -> unsigned(out, 6, BigInteger.valueOf(l));
            case BigInteger b -> unsigned(out, 9, b);
            case Map<?, ?> map -> {
                control(out, 7, map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    encode(out, e.getKey());
                    encode(out, e.getValue());
                }
            }
            case List<?> list -> {
                control(out, 11, list.size());
                for (Object item : list) {
                    encode(out, item);
                }
            }
            default -> throw new IllegalArgumentException("unsupported MMDB value " + value);
        }
    }

    private static void unsigned(ByteArrayOutputStream out, int type, BigInteger value) throws IOException {
        byte[] bytes = value.signum() == 0 ? new byte[0] : value.toByteArray();
        if (bytes.length > 0 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        control(out, type, bytes.length);
        out.write(bytes);
    }

    /** Control byte(s): type in the top 3 bits (extended types 8+ via a second byte), size in the low 5. */
    private static void control(ByteArrayOutputStream out, int type, int size) {
        if (size >= 29) {
            throw new IllegalArgumentException("payloads of 29+ bytes are not needed by these tests");
        }
        if (type <= 7) {
            out.write((type << 5) | size);
        } else {
            out.write(size);
            out.write(type - 7);
        }
    }
}
