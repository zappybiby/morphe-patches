/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads selected protobuf fields as byte slices, without YTM's generated message classes. */
final class AndroidAutoProtoMessage {
    private static final byte[] EMPTY = new byte[0];
    private final byte[] data;
    private final int start;
    private final int end;

    AndroidAutoProtoMessage(byte[] data) { this(data, 0, data.length); }

    private AndroidAutoProtoMessage(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = end;
    }

    boolean isEmpty() { return start == end; }
    String utf8Text() { return new String(data, start, end - start, StandardCharsets.UTF_8); }
    String stringField(int fieldNumber) { return messageField(fieldNumber).utf8Text(); }
    byte[] serializedBytes() { return Arrays.copyOfRange(data, start, end); }

    AndroidAutoProtoMessage messagePath(int... fieldNumbers) {
        AndroidAutoProtoMessage nestedMessage = this;
        for (int fieldNumber : fieldNumbers) nestedMessage = nestedMessage.messageField(fieldNumber);
        return nestedMessage;
    }

    AndroidAutoProtoMessage messageField(int fieldNumber) {
        return messageField(fieldNumber, 2);
    }

    private AndroidAutoProtoMessage messageField(int fieldNumber, int wireType) {
        List<AndroidAutoProtoMessage> matches = repeatedMessageField(fieldNumber, wireType);
        if (matches.size() > 1) throw new IllegalArgumentException("Repeated singular field: " + fieldNumber);
        return matches.isEmpty() ? new AndroidAutoProtoMessage(EMPTY) : matches.get(0);
    }

    long varintField(int fieldNumber) {
        AndroidAutoProtoMessage field = messageField(fieldNumber, 0);
        return field.isEmpty() ? 0 : field.varint(new int[]{field.start});
    }

    List<AndroidAutoProtoMessage> repeatedMessageField(int fieldNumber) {
        return repeatedMessageField(fieldNumber, 2);
    }

    private List<AndroidAutoProtoMessage> repeatedMessageField(int fieldNumber, int expectedWireType) {
        List<AndroidAutoProtoMessage> matches = new ArrayList<>();
        int[] position = {start};
        while (position[0] < end) {
            long tag = varint(position);
            // A protobuf tag packs the field number above three wire-type bits.
            long currentFieldNumber = tag >>> 3;
            int wireType = (int) (tag & 7);
            if (currentFieldNumber == 0 || currentFieldNumber >= (1L << 29))
                throw new IllegalArgumentException("Invalid protobuf tag");
            if (currentFieldNumber == fieldNumber && wireType != expectedWireType)
                throw new IllegalArgumentException("Unexpected protobuf field type");
            if (wireType == 0) {
                int valueStart = position[0];
                varint(position);
                if (currentFieldNumber == fieldNumber) matches.add(new AndroidAutoProtoMessage(data, valueStart, position[0]));
            } else {
                long length;
                if (wireType == 2) length = varint(position);
                else if (wireType == 1) length = 8;
                else if (wireType == 5) length = 4;
                else throw new IllegalArgumentException("Unsupported protobuf wire type");
                if (length < 0 || length > end - position[0]) throw new IllegalArgumentException("Truncated protobuf field");
                if (currentFieldNumber == fieldNumber) {
                    matches.add(new AndroidAutoProtoMessage(data, position[0], position[0] + (int) length));
                }
                position[0] += (int) length;
            }
        }
        return matches;
    }

    private long varint(int[] position) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (position[0] >= end) throw new IllegalArgumentException("Truncated protobuf varint");
            int value = data[position[0]++] & 255;
            if (shift == 63 && value > 1) throw new IllegalArgumentException("Overflowed protobuf varint");
            result |= (long) (value & 127) << shift;
            if ((value & 128) == 0) return result;
        }
        throw new IllegalArgumentException("Invalid protobuf varint");
    }
}
