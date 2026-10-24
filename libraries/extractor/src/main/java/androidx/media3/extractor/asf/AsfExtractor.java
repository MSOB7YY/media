// by claude
package androidx.media3.extractor.asf;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from ASF files ({@code .asf}, {@code .wmv}, {@code .wma}), ported from FFmpeg's
 * demuxer.
 *
 * <p>Every sample is one media object. Files with video seek through the simple index that follows
 * the data packets, the others by searching the packets.
 */
@UnstableApi
public final class AsfExtractor implements Extractor {

  /** Factory for {@link AsfExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new AsfExtractor()};

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({STATE_READING_HEADER, STATE_READING_INDEX, STATE_READING_PACKETS})
  private @interface State {}

  private static final int STATE_READING_HEADER = 0;
  private static final int STATE_READING_INDEX = 1;
  private static final int STATE_READING_PACKETS = 2;

  private static final int OBJECT_HEADER_SIZE = 24;
  private static final int SIMPLE_INDEX_PREFIX_SIZE = 32;
  private static final int SIMPLE_INDEX_ENTRY_SIZE = 6;
  private static final int MAX_SIMPLE_INDEX_SIZE = 1 << 26;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull AsfHeader header;
  private @MonotonicNonNull AsfPacketParser packetParser;
  private @MonotonicNonNull ParsableByteArray packetData;
  private final AsfTrack[] tracksByStreamNumber;
  private final AsfPacketParser.PayloadListener payloadListener;
  @Nullable private AsfBinarySearchSeeker binarySearchSeeker;
  private @State int state;
  private long packetsEndPosition;

  public AsfExtractor() {
    tracksByStreamNumber = new AsfTrack[AsfHeader.MAX_STREAM_NUMBER + 1];
    payloadListener = this::onPayload;
    packetsEndPosition = C.INDEX_UNSET;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    byte[] guid = new byte[AsfGuids.LENGTH];
    return input.peekFully(guid, 0, AsfGuids.LENGTH, /* allowEndOfInput= */ true)
        && AsfGuids.matches(guid, 0, AsfGuids.HEADER_OBJECT);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    switch (state) {
      case STATE_READING_HEADER:
        return readHeader(input, seekPosition);
      case STATE_READING_INDEX:
        return readIndex(input, seekPosition);
      case STATE_READING_PACKETS:
        if (binarySearchSeeker != null && binarySearchSeeker.isSeeking()) {
          return binarySearchSeeker.handlePendingSeek(input, seekPosition);
        }
        return readPacket(input, seekPosition);
      default:
        throw new IllegalStateException();
    }
  }

  @Override
  public void seek(long position, long timeUs) {
    if (packetParser != null) {
      packetParser.reset();
    }
    for (AsfTrack track : tracksByStreamNumber) {
      if (track != null) {
        track.reset();
      }
    }
    if (position != 0 && binarySearchSeeker != null) {
      binarySearchSeeker.setSeekTargetUs(timeUs);
    }
  }

  @Override
  public void release() {
    // Do nothing.
  }

  private @ReadResult int readHeader(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    // A load that's retried from where it stopped can't resume in the middle of the header.
    if (input.getPosition() != 0) {
      seekPosition.position = 0;
      return RESULT_SEEK;
    }
    AsfHeader header = AsfHeader.read(input);
    this.header = header;
    packetParser = new AsfPacketParser(header.packetSize);
    packetData = new ParsableByteArray(header.packetSize);
    outputTracks(header);

    long inputLength = input.getLength();
    long dataEndPosition = header.dataEndPosition;
    if (dataEndPosition == C.INDEX_UNSET) {
      packetsEndPosition = inputLength;
    } else if (inputLength == C.LENGTH_UNSET) {
      packetsEndPosition = dataEndPosition;
    } else {
      packetsEndPosition = Math.min(dataEndPosition, inputLength);
    }

    boolean hasIndexAfterData =
        dataEndPosition != C.INDEX_UNSET
            && inputLength != C.LENGTH_UNSET
            && dataEndPosition < inputLength;
    if (header.hasVideo() && header.durationUs != C.TIME_UNSET && hasIndexAfterData) {
      state = STATE_READING_INDEX;
      seekPosition.position = dataEndPosition;
      return RESULT_SEEK;
    }
    outputSeekMap(/* indexSeekMap= */ null);
    state = STATE_READING_PACKETS;
    return RESULT_CONTINUE;
  }

  private void outputTracks(AsfHeader header) {
    ExtractorOutput extractorOutput = checkNotNull(this.extractorOutput);
    for (int i = 0; i < header.streams.size(); i++) {
      AsfHeader.Stream stream = header.streams.get(i);
      TrackOutput trackOutput = extractorOutput.track(stream.streamNumber, stream.trackType);
      trackOutput.format(stream.format);
      tracksByStreamNumber[stream.streamNumber] =
          new AsfTrack(trackOutput, stream, header.prerollMs);
    }
    extractorOutput.endTracks();
  }

  private @ReadResult int readIndex(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    AsfHeader header = checkNotNull(this.header);
    if (input.getPosition() != header.dataEndPosition) {
      seekPosition.position = header.dataEndPosition;
      return RESULT_SEEK;
    }
    @Nullable IndexSeekMap indexSeekMap = readSimpleIndex(input, header, packetsEndPosition);
    outputSeekMap(indexSeekMap);
    state = STATE_READING_PACKETS;
    seekPosition.position = header.firstPacketPosition;
    return RESULT_SEEK;
  }

  /**
   * Reads the first simple index among the objects after the data object, null when there's
   * none. Entries pointing to the packet of the entry before them are left out.
   */
  @Nullable
  private static IndexSeekMap readSimpleIndex(
      ExtractorInput input, AsfHeader header, long packetsEndPosition) throws IOException {
    long inputLength = input.getLength();
    ParsableByteArray objectHeader = new ParsableByteArray(OBJECT_HEADER_SIZE);
    while (input.getPosition() + OBJECT_HEADER_SIZE <= inputLength) {
      input.readFully(objectHeader.getData(), 0, OBJECT_HEADER_SIZE);
      objectHeader.setPosition(AsfGuids.LENGTH);
      long objectSize = objectHeader.readLittleEndianLong();
      long bodySize = objectSize - OBJECT_HEADER_SIZE;
      if (objectSize < OBJECT_HEADER_SIZE || input.getPosition() + bodySize > inputLength) {
        return null;
      }
      if (!AsfGuids.matches(objectHeader.getData(), 0, AsfGuids.SIMPLE_INDEX_OBJECT)) {
        input.skipFully((int) Math.min(bodySize, Integer.MAX_VALUE));
        continue;
      }
      if (bodySize < SIMPLE_INDEX_PREFIX_SIZE || bodySize > MAX_SIMPLE_INDEX_SIZE) {
        return null;
      }
      ParsableByteArray index = new ParsableByteArray((int) bodySize);
      input.readFully(index.getData(), 0, (int) bodySize);
      return parseSimpleIndex(index, header, packetsEndPosition);
    }
    return null;
  }

  @Nullable
  private static IndexSeekMap parseSimpleIndex(
      ParsableByteArray index, AsfHeader header, long packetsEndPosition) {
    index.setPosition(AsfGuids.LENGTH);
    long entryIntervalUs = index.readLittleEndianLong() / 10;
    index.skipBytes(4);
    long entryCount = index.readLittleEndianUnsignedInt();
    entryCount = Math.min(entryCount, index.bytesLeft() / SIMPLE_INDEX_ENTRY_SIZE);
    if (entryCount < 2 || entryIntervalUs <= 0) {
      return null;
    }
    long prerollUs = header.prerollMs * 1000;
    long[] positions = new long[(int) entryCount];
    long[] timesUs = new long[(int) entryCount];
    int uniqueCount = 0;
    long lastPacketNumber = C.INDEX_UNSET;
    for (int i = 0; i < entryCount; i++) {
      long packetNumber = index.readLittleEndianUnsignedInt();
      index.skipBytes(2);
      long position = header.firstPacketPosition + packetNumber * header.packetSize;
      if (packetNumber == lastPacketNumber || position >= packetsEndPosition) {
        continue;
      }
      long timeUs = Math.max(0, entryIntervalUs * i - prerollUs);
      if (uniqueCount > 0 && timeUs <= timesUs[uniqueCount - 1]) {
        continue;
      }
      lastPacketNumber = packetNumber;
      positions[uniqueCount] = position;
      timesUs[uniqueCount] = timeUs;
      uniqueCount++;
    }
    if (uniqueCount < 2) {
      return null;
    }
    return new IndexSeekMap(
        Arrays.copyOf(positions, uniqueCount),
        Arrays.copyOf(timesUs, uniqueCount),
        header.durationUs);
  }

  private void outputSeekMap(@Nullable IndexSeekMap indexSeekMap) {
    AsfHeader header = checkNotNull(this.header);
    SeekMap seekMap;
    if (indexSeekMap != null) {
      seekMap = indexSeekMap;
    } else if (header.durationUs != C.TIME_UNSET
        && header.durationUs > 0
        && packetsEndPosition != C.INDEX_UNSET) {
      binarySearchSeeker = new AsfBinarySearchSeeker(header, packetsEndPosition);
      seekMap = binarySearchSeeker.getSeekMap();
    } else {
      seekMap = new SeekMap.Unseekable(header.durationUs);
    }
    checkNotNull(extractorOutput).seekMap(seekMap);
  }

  private @ReadResult int readPacket(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    AsfHeader header = checkNotNull(this.header);
    ParsableByteArray packetData = checkNotNull(this.packetData);
    long position = input.getPosition();
    if (packetsEndPosition != C.INDEX_UNSET && position >= packetsEndPosition) {
      return RESULT_END_OF_INPUT;
    }
    long packetSize = header.packetSize;
    long offsetInPackets = position - header.firstPacketPosition;
    if (offsetInPackets < 0 || offsetInPackets % packetSize != 0) {
      long packetIndex = offsetInPackets < 0 ? 0 : offsetInPackets / packetSize + 1;
      seekPosition.position = header.firstPacketPosition + packetIndex * packetSize;
      return RESULT_SEEK;
    }
    int packetLength =
        packetsEndPosition == C.INDEX_UNSET
            ? header.packetSize
            : (int) Math.min(packetSize, packetsEndPosition - position);
    // Peeked first, so a load that fails in the middle of the packet reads it again.
    if (!input.peekFully(packetData.getData(), 0, packetLength, /* allowEndOfInput= */ true)) {
      return RESULT_END_OF_INPUT;
    }
    input.skipFully(packetLength);
    checkNotNull(packetParser).parse(packetData.getData(), packetLength, payloadListener);
    return RESULT_CONTINUE;
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
    @Nullable AsfTrack track = tracksByStreamNumber[streamNumber];
    if (track != null) {
      track.onPayload(isKeyFrame, objectOffset, objectSize, presentationTimeMs, data, offset, length);
    }
  }
}
