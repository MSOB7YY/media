// by claude
package androidx.media3.extractor.asf;

import androidx.media3.common.C;
import androidx.media3.extractor.BinarySearchSeeker;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;

/**
 * A {@link BinarySearchSeeker} over the fixed size data packets of an ASF file without a simple
 * index. A packet's time is the presentation time of its first payload.
 */
/* package */ final class AsfBinarySearchSeeker extends BinarySearchSeeker {

  public AsfBinarySearchSeeker(AsfHeader header, long packetsEndPosition) {
    super(
        new DefaultSeekTimestampConverter(),
        new AsfTimestampSeeker(header, packetsEndPosition),
        header.durationUs,
        /* floorTimePosition= */ 0,
        /* ceilingTimePosition= */ header.durationUs,
        /* floorBytePosition= */ header.firstPacketPosition,
        /* ceilingBytePosition= */ packetsEndPosition,
        /* approxBytesPerFrame= */ header.packetSize,
        /* minimumSearchRange= */ header.packetSize);
  }

  private static final class AsfTimestampSeeker implements TimestampSeeker {

    private static final int MAX_PACKETS_WITHOUT_PAYLOAD = 32;

    private final long prerollUs;
    private final long durationUs;
    private final int packetSize;
    private final long firstPacketPosition;
    private final long packetsEndPosition;
    private final AsfPacketParser packetParser;
    private final byte[] packetData;
    private long firstPayloadTimeMs;

    private AsfTimestampSeeker(AsfHeader header, long packetsEndPosition) {
      prerollUs = header.prerollMs * 1000;
      durationUs = header.durationUs;
      packetSize = header.packetSize;
      firstPacketPosition = header.firstPacketPosition;
      this.packetsEndPosition = packetsEndPosition;
      packetParser = new AsfPacketParser(packetSize);
      packetData = new byte[packetSize];
    }

    /**
     * Reads the first packet with a payload from the search position on, and the packet after it:
     * the target is in the first when it's between their times. A ceiling moves to the search
     * position, which is always below the current one, so the search can't stall on a packet.
     */
    @Override
    public TimestampSearchResult searchForTimestamp(ExtractorInput input, long targetTimeUs)
        throws IOException {
      long searchPosition = input.getPosition();
      long offsetInPackets = Math.max(0, searchPosition - firstPacketPosition);
      long packetIndex = (offsetInPackets + packetSize - 1) / packetSize;
      long packetPosition = firstPacketPosition + packetIndex * packetSize;
      int bytesToPacket = (int) (packetPosition - searchPosition);
      input.advancePeekPosition(bytesToPacket);
      long timeUs = C.TIME_UNSET;
      for (int i = 0;
          i < MAX_PACKETS_WITHOUT_PAYLOAD
              && timeUs == C.TIME_UNSET
              && packetPosition < packetsEndPosition;
          i++) {
        timeUs = peekPacketTimeUs(input, packetPosition);
        if (timeUs == C.TIME_UNSET) {
          packetPosition += packetSize;
        }
      }
      if (timeUs == C.TIME_UNSET || timeUs > targetTimeUs) {
        long ceilingTimeUs =
            timeUs == C.TIME_UNSET ? Math.max(durationUs, targetTimeUs + 1) : timeUs;
        return TimestampSearchResult.overestimatedResult(ceilingTimeUs, searchPosition);
      }
      long nextPacketPosition = packetPosition + packetSize;
      long nextTimeUs =
          nextPacketPosition < packetsEndPosition
              ? peekPacketTimeUs(input, nextPacketPosition)
              : C.TIME_UNSET;
      if (nextTimeUs == C.TIME_UNSET || nextTimeUs > targetTimeUs) {
        return TimestampSearchResult.targetFoundResult(packetPosition);
      }
      return TimestampSearchResult.underestimatedResult(nextTimeUs, nextPacketPosition);
    }

    /** Peeks the packet at the peek position, {@link C#TIME_UNSET} when it has no payload. */
    private long peekPacketTimeUs(ExtractorInput input, long packetPosition) throws IOException {
      int packetLength = (int) Math.min(packetSize, packetsEndPosition - packetPosition);
      if (!input.peekFully(packetData, 0, packetLength, /* allowEndOfInput= */ true)) {
        return C.TIME_UNSET;
      }
      firstPayloadTimeMs = C.TIME_UNSET;
      packetParser.reset();
      packetParser.parse(packetData, packetLength, this::onPayload);
      return firstPayloadTimeMs == C.TIME_UNSET ? C.TIME_UNSET : firstPayloadTimeMs * 1000 - prerollUs;
    }

    private void onPayload(
        int streamNumber,
        boolean isKeyFrame,
        int objectOffset,
        int objectSize,
        long presentationTimeMs,
        byte[] data,
        int offset,
        int length) {
      if (firstPayloadTimeMs == C.TIME_UNSET) {
        firstPayloadTimeMs = presentationTimeMs;
      }
    }
  }
}
