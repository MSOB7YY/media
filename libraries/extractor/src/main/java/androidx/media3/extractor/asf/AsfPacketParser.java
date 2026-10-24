// by claude
package androidx.media3.extractor.asf;

import java.util.Arrays;

/**
 * Splits ASF data packets into payloads, following FFmpeg's demuxer. A malformed packet ends at
 * the first payload that can't be read.
 */
/* package */ final class AsfPacketParser {

  private static final int FLAG_ERROR_CORRECTION_PRESENT = 0x80;
  private static final int ERROR_CORRECTION_LENGTH_TYPE_MASK = 0x60;
  private static final int ERROR_CORRECTION_LENGTH_MASK = 0x0F;
  private static final int FLAG_MULTIPLE_PAYLOADS = 0x01;
  private static final int FLAG_KEY_FRAME = 0x80;
  private static final int STREAM_NUMBER_MASK = 0x7F;
  private static final int PAYLOAD_COUNT_MASK = 0x3F;

  /** The replicated data of a compressed payload is its presentation time delta. */
  private static final int COMPRESSED_REPLICATED_DATA_LENGTH = 1;

  private static final int MIN_REPLICATED_DATA_LENGTH = 8;

  /** Send time and duration. */
  private static final int PACKET_TIMING_SIZE = 6;

  /** FFmpeg stops reading payloads when fewer bytes are left. */
  private static final int MIN_PAYLOAD_SIZE = 6;

  private static final int MAX_OBJECT_SIZE = 1 << 24;
  private static final int MAX_PACKET_LENGTH = 1 << 29;

  private final int packetSize;

  /** Payloads without replicated data belong to the last media object. */
  private final int[] lastObjectSizes;

  private long lastPresentationTimeMs;

  public AsfPacketParser(int packetSize) {
    this.packetSize = packetSize;
    lastObjectSizes = new int[AsfHeader.MAX_STREAM_NUMBER + 1];
  }

  public void reset() {
    Arrays.fill(lastObjectSizes, 0);
    lastPresentationTimeMs = 0;
  }

  /** Parses the packet in {@code data[0, length)}, shorter than the packet size when truncated. */
  public void parse(byte[] data, int length, PayloadListener listener) {
    if (length < 1) {
      return;
    }
    int position = 0;
    int flags = data[position++] & 0xFF;
    if ((flags & FLAG_ERROR_CORRECTION_PRESENT) != 0) {
      if ((flags & ERROR_CORRECTION_LENGTH_TYPE_MASK) == 0) {
        position += flags & ERROR_CORRECTION_LENGTH_MASK;
      }
      if (position >= length) {
        return;
      }
      flags = data[position++] & 0xFF;
    }

    int packetLengthType = flags >> 5;
    int sequenceType = flags >> 1;
    int paddingLengthType = flags >> 3;
    int packetHeaderSize =
        1
            + getFieldSize(packetLengthType)
            + getFieldSize(sequenceType)
            + getFieldSize(paddingLengthType)
            + PACKET_TIMING_SIZE;
    if (position + packetHeaderSize > length) {
      return;
    }
    int properties = data[position++] & 0xFF;
    long packetLength = readField(data, position, packetLengthType, packetSize);
    position += getFieldSize(packetLengthType) + getFieldSize(sequenceType);
    long paddingLength = readField(data, position, paddingLengthType, 0);
    position += getFieldSize(paddingLengthType);
    long sendTimeMs = readUnsignedInt(data, position);
    position += PACKET_TIMING_SIZE;
    if (packetLength == 0 || packetLength >= MAX_PACKET_LENGTH || paddingLength >= packetLength) {
      return;
    }

    boolean hasMultiplePayloads = (flags & FLAG_MULTIPLE_PAYLOADS) != 0;
    int payloadCount = 1;
    int payloadLengthType = 0;
    if (hasMultiplePayloads) {
      if (position >= length) {
        return;
      }
      int payloadFlags = data[position++] & 0xFF;
      payloadCount = payloadFlags & PAYLOAD_COUNT_MASK;
      payloadLengthType = payloadFlags >> 6;
    }
    if (position > packetLength - paddingLength) {
      return;
    }
    long sizeLeft = packetLength - paddingLength - position;
    long remainingPadding = paddingLength + Math.max(0, packetSize - packetLength);

    int objectNumberType = properties >> 4;
    int objectOffsetType = properties >> 2;
    int replicatedDataLengthType = properties;
    int payloadHeaderSize =
        1
            + getFieldSize(objectNumberType)
            + getFieldSize(objectOffsetType)
            + getFieldSize(replicatedDataLengthType);
    while (payloadCount > 0 && sizeLeft >= MIN_PAYLOAD_SIZE) {
      payloadCount--;
      if (position + payloadHeaderSize > length) {
        return;
      }
      int streamFlags = data[position] & 0xFF;
      int streamNumber = streamFlags & STREAM_NUMBER_MASK;
      boolean isKeyFrame = (streamFlags & FLAG_KEY_FRAME) != 0;
      int fieldPosition = position + 1 + getFieldSize(objectNumberType);
      long objectOffset = readField(data, fieldPosition, objectOffsetType, 0);
      fieldPosition += getFieldSize(objectOffsetType);
      long replicatedDataLength = readField(data, fieldPosition, replicatedDataLengthType, 0);
      fieldPosition += getFieldSize(replicatedDataLengthType);
      long headerSize = payloadHeaderSize;
      if (headerSize + replicatedDataLength > sizeLeft) {
        return;
      }

      int objectSize;
      long presentationTimeMs;
      boolean isCompressed = replicatedDataLength == COMPRESSED_REPLICATED_DATA_LENGTH;
      long compressedTimeMs = 0;
      int compressedTimeDeltaMs = 0;
      if (replicatedDataLength >= MIN_REPLICATED_DATA_LENGTH) {
        if (fieldPosition + MIN_REPLICATED_DATA_LENGTH > length) {
          return;
        }
        objectSize = readInt(data, fieldPosition);
        if (objectSize < 0 || objectSize >= MAX_OBJECT_SIZE) {
          return;
        }
        presentationTimeMs = readUnsignedInt(data, fieldPosition + 4);
        lastObjectSizes[streamNumber] = objectSize;
        lastPresentationTimeMs = presentationTimeMs;
        headerSize += replicatedDataLength;
      } else if (isCompressed) {
        if (fieldPosition >= length) {
          return;
        }
        // The offset field holds the presentation time of the first sub payload.
        compressedTimeMs = objectOffset;
        compressedTimeDeltaMs = data[fieldPosition] & 0xFF;
        objectOffset = 0;
        objectSize = 0;
        presentationTimeMs = sendTimeMs;
        headerSize++;
      } else if (replicatedDataLength == 0) {
        objectSize = lastObjectSizes[streamNumber];
        presentationTimeMs = lastPresentationTimeMs;
      } else {
        return;
      }

      long payloadLength;
      if (hasMultiplePayloads) {
        int lengthFieldPosition = (int) (position + headerSize);
        if (lengthFieldPosition + getFieldSize(payloadLengthType) > length) {
          return;
        }
        payloadLength = readField(data, lengthFieldPosition, payloadLengthType, 0);
        headerSize += getFieldSize(payloadLengthType);
        if (headerSize > sizeLeft) {
          return;
        }
        if (payloadLength > sizeLeft - headerSize) {
          long paddingUsed = payloadLength - (sizeLeft - headerSize);
          if (paddingUsed > remainingPadding) {
            return;
          }
          sizeLeft += paddingUsed;
          remainingPadding -= paddingUsed;
        }
      } else {
        payloadLength = sizeLeft - headerSize;
      }
      if (isCompressed && payloadLength > sizeLeft) {
        return;
      }
      sizeLeft -= headerSize + payloadLength;
      position += (int) headerSize;

      if (isCompressed) {
        long timeMs = compressedTimeMs;
        long payloadEnd = position + payloadLength;
        while (position < payloadEnd) {
          int subPayloadSize = data[position++] & 0xFF;
          if (payloadEnd - position < subPayloadSize) {
            break;
          }
          if (position + subPayloadSize > length) {
            return;
          }
          if (subPayloadSize > 0) {
            listener.onPayload(
                streamNumber,
                isKeyFrame,
                /* objectOffset= */ 0,
                subPayloadSize,
                timeMs,
                data,
                position,
                subPayloadSize);
          }
          lastPresentationTimeMs = timeMs;
          position += subPayloadSize;
          timeMs += compressedTimeDeltaMs;
        }
        position = (int) payloadEnd;
      } else {
        int availableLength = (int) Math.min(payloadLength, length - position);
        if (availableLength > 0) {
          listener.onPayload(
              streamNumber,
              isKeyFrame,
              (int) objectOffset,
              objectSize,
              presentationTimeMs,
              data,
              position,
              availableLength);
        }
        position += (int) payloadLength;
      }
      if (position >= length) {
        return;
      }
    }
  }

  private static int getFieldSize(int lengthType) {
    switch (lengthType & 3) {
      case 1:
        return 1;
      case 2:
        return 2;
      case 3:
        return 4;
      default:
        return 0;
    }
  }

  private static long readField(byte[] data, int position, int lengthType, long defaultValue) {
    switch (lengthType & 3) {
      case 1:
        return data[position] & 0xFF;
      case 2:
        return (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8;
      case 3:
        return readUnsignedInt(data, position);
      default:
        return defaultValue;
    }
  }

  private static int readInt(byte[] data, int position) {
    return (data[position] & 0xFF)
        | (data[position + 1] & 0xFF) << 8
        | (data[position + 2] & 0xFF) << 16
        | (data[position + 3] & 0xFF) << 24;
  }

  private static long readUnsignedInt(byte[] data, int position) {
    return readInt(data, position) & 0xFFFFFFFFL;
  }

  /** Receives the payloads of a packet, in their order in the packet. */
  public interface PayloadListener {

    /**
     * Called for each payload holding data.
     *
     * @param streamNumber The stream of the media object.
     * @param isKeyFrame Whether the media object is a key frame.
     * @param objectOffset Where the payload data goes in the media object.
     * @param objectSize The size of the media object.
     * @param presentationTimeMs The presentation time of the media object, preroll included.
     * @param data The packet data.
     * @param offset The start of the payload data in {@code data}.
     * @param length The length of the payload data.
     */
    void onPayload(
        int streamNumber,
        boolean isKeyFrame,
        int objectOffset,
        int objectSize,
        long presentationTimeMs,
        byte[] data,
        int offset,
        int length);
  }
}
