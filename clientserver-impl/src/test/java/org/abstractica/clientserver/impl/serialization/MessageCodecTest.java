package org.abstractica.clientserver.impl.serialization;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageCodec}.
 */
class MessageCodecTest
{
    // ========== Test Records ==========

    record SimpleInts(int x, int y) {}
    record AllPrimitives(byte b, short s, int i, long l, float f, double d, boolean bool, char c) {}
    record WithString(String name, int value) {}
    record WithByteArray(byte[] data, String label) {}
    record WithList(List<Integer> numbers) {}
    record WithOptional(Optional<String> maybe, int required) {}
    record WithEnum(Color color, int value) {}
    record Nested(Point point, String label) {}
    record Point(int x, int y) {}
    record ComplexNested(List<Point> points, Optional<Color> color) {}

    enum Color { RED, GREEN, BLUE }

    // ========== Primitive Tests ==========

    @Test
    void encode_decode_simpleInts()
    {
        SimpleInts original = new SimpleInts(100, 200);
        byte[] encoded = MessageCodec.encode(original);
        SimpleInts decoded = MessageCodec.decode(encoded, SimpleInts.class);

        assertEquals(original, decoded);
        assertEquals(8, encoded.length); // 2 ints = 8 bytes
    }

    @Test
    void encode_decode_allPrimitives()
    {
        AllPrimitives original = new AllPrimitives(
                (byte) 42,
                (short) 1000,
                123456,
                9876543210L,
                3.14f,
                2.71828,
                true,
                'X'
        );
        byte[] encoded = MessageCodec.encode(original);
        AllPrimitives decoded = MessageCodec.decode(encoded, AllPrimitives.class);

        assertEquals(original, decoded);
    }

    @Test
    void encode_decode_negativeNumbers()
    {
        SimpleInts original = new SimpleInts(-100, Integer.MIN_VALUE);
        byte[] encoded = MessageCodec.encode(original);
        SimpleInts decoded = MessageCodec.decode(encoded, SimpleInts.class);

        assertEquals(original, decoded);
    }

    // ========== String Tests ==========

    @Test
    void encode_decode_withString()
    {
        WithString original = new WithString("Hello, World!", 42);
        byte[] encoded = MessageCodec.encode(original);
        WithString decoded = MessageCodec.decode(encoded, WithString.class);

        assertEquals(original, decoded);
    }

    @Test
    void encode_decode_emptyString()
    {
        WithString original = new WithString("", 0);
        byte[] encoded = MessageCodec.encode(original);
        WithString decoded = MessageCodec.decode(encoded, WithString.class);

        assertEquals(original, decoded);
    }

    @Test
    void encode_decode_unicodeString()
    {
        WithString original = new WithString("こんにちは 🌍 émojis", 123);
        byte[] encoded = MessageCodec.encode(original);
        WithString decoded = MessageCodec.decode(encoded, WithString.class);

        assertEquals(original, decoded);
    }

    // ========== byte[] Tests ==========

    @Test
    void encode_decode_withByteArray()
    {
        byte[] data = {0x01, 0x02, 0x03, (byte) 0xFF};
        WithByteArray original = new WithByteArray(data, "test");
        byte[] encoded = MessageCodec.encode(original);
        WithByteArray decoded = MessageCodec.decode(encoded, WithByteArray.class);

        assertEquals(original.label(), decoded.label());
        assertArrayEquals(original.data(), decoded.data());
    }

    @Test
    void encode_decode_emptyByteArray()
    {
        WithByteArray original = new WithByteArray(new byte[0], "empty");
        byte[] encoded = MessageCodec.encode(original);
        WithByteArray decoded = MessageCodec.decode(encoded, WithByteArray.class);

        assertArrayEquals(new byte[0], decoded.data());
    }

    // ========== List Tests ==========

    @Test
    void encode_decode_withList()
    {
        WithList original = new WithList(List.of(1, 2, 3, 4, 5));
        byte[] encoded = MessageCodec.encode(original);
        WithList decoded = MessageCodec.decode(encoded, WithList.class);

        assertEquals(original, decoded);
    }

    @Test
    void encode_decode_emptyList()
    {
        WithList original = new WithList(List.of());
        byte[] encoded = MessageCodec.encode(original);
        WithList decoded = MessageCodec.decode(encoded, WithList.class);

        assertEquals(original, decoded);
    }

    // ========== Optional Tests ==========

    @Test
    void encode_decode_optionalPresent()
    {
        WithOptional original = new WithOptional(Optional.of("present"), 42);
        byte[] encoded = MessageCodec.encode(original);
        WithOptional decoded = MessageCodec.decode(encoded, WithOptional.class);

        assertEquals(original, decoded);
    }

    @Test
    void encode_decode_optionalEmpty()
    {
        WithOptional original = new WithOptional(Optional.empty(), 42);
        byte[] encoded = MessageCodec.encode(original);
        WithOptional decoded = MessageCodec.decode(encoded, WithOptional.class);

        assertEquals(original, decoded);
    }

    // ========== Enum Tests ==========

    @Test
    void encode_decode_withEnum()
    {
        WithEnum original = new WithEnum(Color.GREEN, 100);
        byte[] encoded = MessageCodec.encode(original);
        WithEnum decoded = MessageCodec.decode(encoded, WithEnum.class);

        assertEquals(original, decoded);
    }

    @Test
    void encode_decode_allEnumValues()
    {
        for (Color color : Color.values())
        {
            WithEnum original = new WithEnum(color, color.ordinal());
            byte[] encoded = MessageCodec.encode(original);
            WithEnum decoded = MessageCodec.decode(encoded, WithEnum.class);

            assertEquals(original, decoded);
        }
    }

    // ========== Nested Record Tests ==========

    @Test
    void encode_decode_nestedRecord()
    {
        Nested original = new Nested(new Point(10, 20), "origin");
        byte[] encoded = MessageCodec.encode(original);
        Nested decoded = MessageCodec.decode(encoded, Nested.class);

        assertEquals(original, decoded);
    }

    @Test
    void encode_decode_complexNested()
    {
        ComplexNested original = new ComplexNested(
                List.of(new Point(0, 0), new Point(1, 1), new Point(2, 2)),
                Optional.of(Color.RED)
        );
        byte[] encoded = MessageCodec.encode(original);
        ComplexNested decoded = MessageCodec.decode(encoded, ComplexNested.class);

        assertEquals(original, decoded);
    }

    // ========== Size Calculation Tests ==========

    @Test
    void calculateSize_correctForSimpleInts()
    {
        SimpleInts record = new SimpleInts(1, 2);
        assertEquals(8, MessageCodec.calculateSize(record));
    }

    @Test
    void calculateSize_correctForString()
    {
        WithString record = new WithString("test", 0);
        // 2 (length) + 4 (bytes) + 4 (int) = 10
        assertEquals(10, MessageCodec.calculateSize(record));
    }

    @Test
    void calculateSize_correctForList()
    {
        WithList record = new WithList(List.of(1, 2, 3));
        // 2 (count) + 3 * 4 (ints) = 14
        assertEquals(14, MessageCodec.calculateSize(record));
    }

    // ========== Wire Format Tests ==========

    @Test
    void encode_simpleInts_wireFormat()
    {
        SimpleInts record = new SimpleInts(0x12345678, 0xABCDEF01);
        byte[] encoded = MessageCodec.encode(record);

        assertEquals(8, encoded.length);
        // Big-endian int 0x12345678
        assertEquals((byte) 0x12, encoded[0]);
        assertEquals((byte) 0x34, encoded[1]);
        assertEquals((byte) 0x56, encoded[2]);
        assertEquals((byte) 0x78, encoded[3]);
        // Big-endian int 0xABCDEF01
        assertEquals((byte) 0xAB, encoded[4]);
        assertEquals((byte) 0xCD, encoded[5]);
        assertEquals((byte) 0xEF, encoded[6]);
        assertEquals((byte) 0x01, encoded[7]);
    }

    @Test
    void encode_string_wireFormat()
    {
        WithString record = new WithString("AB", 0);
        byte[] encoded = MessageCodec.encode(record);

        // 2 bytes length + 2 bytes "AB" + 4 bytes int
        assertEquals(8, encoded.length);
        assertEquals(0, encoded[0]); // length high byte
        assertEquals(2, encoded[1]); // length low byte
        assertEquals('A', encoded[2]);
        assertEquals('B', encoded[3]);
    }

    @Test
    void encode_boolean_wireFormat()
    {
        record BoolRecord(boolean value) {}

        byte[] trueEncoded = MessageCodec.encode(new BoolRecord(true));
        byte[] falseEncoded = MessageCodec.encode(new BoolRecord(false));

        assertEquals(1, trueEncoded[0]);
        assertEquals(0, falseEncoded[0]);
    }

    @Test
    void encode_optional_wireFormat()
    {
        WithOptional present = new WithOptional(Optional.of("X"), 0);
        WithOptional absent = new WithOptional(Optional.empty(), 0);

        byte[] presentEncoded = MessageCodec.encode(present);
        byte[] absentEncoded = MessageCodec.encode(absent);

        assertEquals(1, presentEncoded[0]); // presence byte = 1
        assertEquals(0, absentEncoded[0]);  // presence byte = 0
    }

    @Test
    void encode_enum_wireFormat()
    {
        WithEnum record = new WithEnum(Color.BLUE, 0); // BLUE ordinal = 2
        byte[] encoded = MessageCodec.encode(record);

        assertEquals(0, encoded[0]); // ordinal high byte
        assertEquals(2, encoded[1]); // ordinal low byte (BLUE = 2)
    }
}
