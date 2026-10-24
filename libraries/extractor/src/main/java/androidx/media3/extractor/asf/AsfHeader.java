// by claude
package androidx.media3.extractor.asf;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The ASF header objects, from the start of the file up to the first data packet. */
/* package */ final class AsfHeader {

  public static final int MAX_STREAM_NUMBER = 127;

  private static final int HEADER_OBJECT_PREFIX_SIZE = 30;
  private static final int OBJECT_HEADER_SIZE = 24;
  private static final int HEADER_EXTENSION_PREFIX_SIZE = 22;
  private static final int DATA_OBJECT_PREFIX_SIZE = 26;

  /** FFmpeg doesn't trust the size of smaller data objects. */
  private static final int MIN_SIZED_DATA_OBJECT_SIZE = 100;

  private static final int MAX_PARSED_OBJECT_SIZE = 1 << 20;
  private static final int FILE_PROPERTIES_SIZE = 80;
  private static final int STREAM_PROPERTIES_PREFIX_SIZE = 54;
  private static final int EXTENDED_STREAM_PROPERTIES_PREFIX_SIZE = 64;
  private static final int AUDIO_SPREAD_DATA_SIZE = 7;

  private static final int FLAG_BROADCAST = 0x01;
  private static final int STREAM_NUMBER_MASK = 0x7F;
  private static final int STREAM_FLAG_ENCRYPTED = 0x8000;

  public final long prerollMs;
  public final long durationUs;
  public final boolean isBroadcast;
  public final int packetSize;
  public final long firstPacketPosition;

  /** The end of the data object, {@link C#INDEX_UNSET} when its size isn't known. */
  public final long dataEndPosition;

  public final ImmutableList<Stream> streams;

  private AsfHeader(
      long prerollMs,
      long durationUs,
      boolean isBroadcast,
      int packetSize,
      long firstPacketPosition,
      long dataEndPosition,
      ImmutableList<Stream> streams) {
    this.prerollMs = prerollMs;
    this.durationUs = durationUs;
    this.isBroadcast = isBroadcast;
    this.packetSize = packetSize;
    this.firstPacketPosition = firstPacketPosition;
    this.dataEndPosition = dataEndPosition;
    this.streams = streams;
  }

  public boolean hasVideo() {
    for (int i = 0; i < streams.size(); i++) {
      if (streams.get(i).trackType == C.TRACK_TYPE_VIDEO) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads the header from the start of {@code input}, leaving it at the first data packet.
   *
   * <p>Objects inside the header extension are read like the ones next to it, and a stream
   * properties object inside an extended stream properties object like a standalone one, the way
   * FFmpeg does.
   */
  public static AsfHeader read(ExtractorInput input) throws IOException {
    ParsableByteArray scratch = new ParsableByteArray(HEADER_OBJECT_PREFIX_SIZE);
    byte[] scratchData = scratch.getData();
    input.readFully(scratchData, 0, HEADER_OBJECT_PREFIX_SIZE);
    if (!AsfGuids.matches(scratchData, 0, AsfGuids.HEADER_OBJECT)) {
      throw ParserException.createForMalformedContainer("Missing ASF header object", null);
    }

    @Nullable ParsableByteArray fileProperties = null;
    List<ParsableByteArray> streamProperties = new ArrayList<>();
    SparseArray<ExtendedStreamProperties> extendedProperties = new SparseArray<>();
    long firstPacketPosition;
    long dataObjectPosition;
    long dataObjectSize;
    while (true) {
      long objectPosition = input.getPosition();
      input.readFully(scratchData, 0, OBJECT_HEADER_SIZE);
      scratch.setPosition(AsfGuids.LENGTH);
      long objectSize = scratch.readLittleEndianLong();
      if (AsfGuids.matches(scratchData, 0, AsfGuids.DATA_OBJECT)) {
        input.readFully(scratchData, 0, DATA_OBJECT_PREFIX_SIZE);
        dataObjectPosition = objectPosition;
        dataObjectSize = objectSize;
        firstPacketPosition = input.getPosition();
        break;
      }
      if (objectSize < OBJECT_HEADER_SIZE) {
        throw ParserException.createForMalformedContainer("Invalid ASF object size", null);
      }
      long bodySize = objectSize - OBJECT_HEADER_SIZE;
      if (AsfGuids.matches(scratchData, 0, AsfGuids.FILE_PROPERTIES_OBJECT)) {
        fileProperties = readBody(input, bodySize);
      } else if (AsfGuids.matches(scratchData, 0, AsfGuids.STREAM_PROPERTIES_OBJECT)) {
        ParsableByteArray body = readBody(input, bodySize);
        streamProperties.add(body);
      } else if (AsfGuids.matches(scratchData, 0, AsfGuids.EXTENDED_STREAM_PROPERTIES_OBJECT)) {
        ParsableByteArray body = readBody(input, bodySize);
        parseExtendedStreamProperties(body, extendedProperties, streamProperties);
      } else if (AsfGuids.matches(scratchData, 0, AsfGuids.HEADER_EXTENSION_OBJECT)
          && bodySize >= HEADER_EXTENSION_PREFIX_SIZE) {
        input.skipFully(HEADER_EXTENSION_PREFIX_SIZE);
      } else {
        skip(input, bodySize);
      }
    }

    if (fileProperties == null || fileProperties.limit() < FILE_PROPERTIES_SIZE) {
      throw ParserException.createForMalformedContainer("Missing ASF file properties", null);
    }
    fileProperties.setPosition(40);
    long playDuration = fileProperties.readLittleEndianLong();
    fileProperties.skipBytes(8);
    long prerollMs = fileProperties.readLittleEndianUnsignedInt();
    fileProperties.skipBytes(4);
    boolean isBroadcast = (fileProperties.readLittleEndianInt() & FLAG_BROADCAST) != 0;
    int minPacketSize = fileProperties.readLittleEndianInt();
    int maxPacketSize = fileProperties.readLittleEndianInt();
    if (minPacketSize != maxPacketSize || maxPacketSize <= 0 || maxPacketSize >= 1 << 29) {
      throw ParserException.createForMalformedContainer(
          "Unsupported ASF packet size " + minPacketSize + "-" + maxPacketSize, null);
    }
    long playDurationUs = playDuration / 10 - prerollMs * 1000;
    long durationUs = isBroadcast || playDurationUs <= 0 ? C.TIME_UNSET : playDurationUs;
    long dataEndPosition =
        isBroadcast || dataObjectSize < MIN_SIZED_DATA_OBJECT_SIZE
            ? C.INDEX_UNSET
            : dataObjectPosition + dataObjectSize;

    ImmutableList.Builder<Stream> streams = ImmutableList.builder();
    boolean[] hasStream = new boolean[MAX_STREAM_NUMBER + 1];
    for (int i = 0; i < streamProperties.size(); i++) {
      @Nullable
      Stream stream = parseStreamProperties(streamProperties.get(i), extendedProperties);
      if (stream != null && !hasStream[stream.streamNumber]) {
        hasStream[stream.streamNumber] = true;
        streams.add(stream);
      }
    }
    return new AsfHeader(
        prerollMs,
        durationUs,
        isBroadcast,
        maxPacketSize,
        firstPacketPosition,
        dataEndPosition,
        streams.build());
  }

  @Nullable
  private static Stream parseStreamProperties(
      ParsableByteArray body, SparseArray<ExtendedStreamProperties> extendedProperties) {
    if (body.limit() < STREAM_PROPERTIES_PREFIX_SIZE) {
      return null;
    }
    byte[] data = body.getData();
    boolean isAudio = AsfGuids.matches(data, 0, AsfGuids.AUDIO_MEDIA);
    boolean isVideo = AsfGuids.matches(data, 0, AsfGuids.VIDEO_MEDIA);
    boolean hasAudioSpread = AsfGuids.matches(data, AsfGuids.LENGTH, AsfGuids.AUDIO_SPREAD);
    body.setPosition(40);
    long typeSpecificSize = body.readLittleEndianUnsignedInt();
    long errorCorrectionSize = body.readLittleEndianUnsignedInt();
    int flags = body.readLittleEndianUnsignedShort();
    int streamNumber = flags & STREAM_NUMBER_MASK;
    boolean isEncrypted = (flags & STREAM_FLAG_ENCRYPTED) != 0;
    int typeSpecificOffset = STREAM_PROPERTIES_PREFIX_SIZE;
    if ((!isAudio && !isVideo)
        || isEncrypted
        || streamNumber == 0
        || typeSpecificSize > body.limit() - typeSpecificOffset) {
      return null;
    }
    byte[] typeSpecificData = new byte[(int) typeSpecificSize];
    System.arraycopy(data, typeSpecificOffset, typeSpecificData, 0, typeSpecificData.length);

    @Nullable
    Format.Builder format =
        isAudio
            ? AsfFormats.getAudioFormat(typeSpecificData)
            : AsfFormats.getVideoFormat(typeSpecificData);
    if (format == null) {
      return null;
    }
    format.setId(streamNumber).setContainerMimeType(MimeTypes.VIDEO_ASF);
    @Nullable ExtendedStreamProperties extended = extendedProperties.get(streamNumber);
    if (extended != null) {
      extended.applyTo(format, isVideo);
    }

    int descramblingSpan = 0;
    int descramblingPacketSize = 0;
    int descramblingChunkSize = 0;
    int errorCorrectionOffset = typeSpecificOffset + typeSpecificData.length;
    if (isAudio
        && hasAudioSpread
        && errorCorrectionSize >= AUDIO_SPREAD_DATA_SIZE
        && body.limit() - errorCorrectionOffset >= AUDIO_SPREAD_DATA_SIZE) {
      body.setPosition(errorCorrectionOffset);
      descramblingSpan = body.readUnsignedByte();
      descramblingPacketSize = body.readLittleEndianUnsignedShort();
      descramblingChunkSize = body.readLittleEndianUnsignedShort();
      boolean isDescramblingValid =
          descramblingChunkSize > 0
              && descramblingPacketSize / descramblingChunkSize > 1
              && descramblingPacketSize % descramblingChunkSize == 0;
      if (descramblingSpan > 1 && !isDescramblingValid) {
        descramblingSpan = 0;
      }
    }
    @C.TrackType int trackType = isAudio ? C.TRACK_TYPE_AUDIO : C.TRACK_TYPE_VIDEO;
    return new Stream(
        streamNumber,
        trackType,
        format.build(),
        descramblingSpan,
        descramblingPacketSize,
        descramblingChunkSize);
  }

  private static void parseExtendedStreamProperties(
      ParsableByteArray body,
      SparseArray<ExtendedStreamProperties> extendedProperties,
      List<ParsableByteArray> streamProperties) {
    if (body.limit() < EXTENDED_STREAM_PROPERTIES_PREFIX_SIZE) {
      return;
    }
    body.setPosition(16);
    long dataBitrate = body.readLittleEndianUnsignedInt();
    body.skipBytes(20);
    long maxObjectSize = body.readLittleEndianUnsignedInt();
    body.skipBytes(4);
    int streamNumber = body.readLittleEndianUnsignedShort();
    body.skipBytes(2);
    long averageTimePerFrame = body.readLittleEndianLong();
    int streamNameCount = body.readLittleEndianUnsignedShort();
    int payloadExtensionCount = body.readLittleEndianUnsignedShort();
    if (streamNumber <= MAX_STREAM_NUMBER) {
      ExtendedStreamProperties properties =
          new ExtendedStreamProperties(dataBitrate, maxObjectSize, averageTimePerFrame);
      extendedProperties.put(streamNumber, properties);
    }

    for (int i = 0; i < streamNameCount; i++) {
      if (body.bytesLeft() < 4) {
        return;
      }
      body.skipBytes(2);
      int nameLength = body.readLittleEndianUnsignedShort();
      if (body.bytesLeft() < nameLength) {
        return;
      }
      body.skipBytes(nameLength);
    }
    for (int i = 0; i < payloadExtensionCount; i++) {
      if (body.bytesLeft() < AsfGuids.LENGTH + 6) {
        return;
      }
      body.skipBytes(AsfGuids.LENGTH + 2);
      long infoLength = body.readLittleEndianUnsignedInt();
      if (body.bytesLeft() < infoLength) {
        return;
      }
      body.skipBytes((int) infoLength);
    }

    int embeddedPosition = body.getPosition();
    if (body.bytesLeft() > OBJECT_HEADER_SIZE
        && AsfGuids.matches(body.getData(), embeddedPosition, AsfGuids.STREAM_PROPERTIES_OBJECT)) {
      int embeddedSize = body.limit() - embeddedPosition - OBJECT_HEADER_SIZE;
      ParsableByteArray embedded = new ParsableByteArray(embeddedSize);
      System.arraycopy(
          body.getData(),
          embeddedPosition + OBJECT_HEADER_SIZE,
          embedded.getData(),
          0,
          embeddedSize);
      streamProperties.add(embedded);
    }
  }

  private static ParsableByteArray readBody(ExtractorInput input, long bodySize)
      throws IOException {
    if (bodySize > MAX_PARSED_OBJECT_SIZE) {
      throw ParserException.createForMalformedContainer("ASF header object too large", null);
    }
    ParsableByteArray body = new ParsableByteArray((int) bodySize);
    input.readFully(body.getData(), 0, (int) bodySize);
    return body;
  }

  private static void skip(ExtractorInput input, long length) throws IOException {
    while (length > 0) {
      int skipLength = (int) Math.min(length, Integer.MAX_VALUE);
      input.skipFully(skipLength);
      length -= skipLength;
    }
  }

  /** The values of an extended stream properties object the formats use. */
  private static final class ExtendedStreamProperties {

    private static final long TIME_UNITS_PER_SECOND = 10_000_000;

    private final long dataBitrate;
    private final long maxObjectSize;

    /** In 100 nanosecond units. */
    private final long averageTimePerFrame;

    private ExtendedStreamProperties(
        long dataBitrate, long maxObjectSize, long averageTimePerFrame) {
      this.dataBitrate = dataBitrate;
      this.maxObjectSize = maxObjectSize;
      this.averageTimePerFrame = averageTimePerFrame;
    }

    private void applyTo(Format.Builder format, boolean isVideo) {
      if (maxObjectSize > 0 && maxObjectSize <= Integer.MAX_VALUE) {
        format.setMaxInputSize((int) maxObjectSize);
      }
      if (isVideo) {
        if (dataBitrate > 0 && dataBitrate <= Integer.MAX_VALUE) {
          format.setAverageBitrate((int) dataBitrate);
        }
        if (averageTimePerFrame > 0) {
          format.setFrameRate((float) TIME_UNITS_PER_SECOND / averageTimePerFrame);
        }
      }
    }
  }

  /** A stream the header declares, with a format that can be played. */
  public static final class Stream {

    public final int streamNumber;
    public final @C.TrackType int trackType;
    public final Format format;

    /** Audio objects made of {@code descramblingSpan} rows of chunks are stored by column. */
    public final int descramblingSpan;

    public final int descramblingPacketSize;
    public final int descramblingChunkSize;

    private Stream(
        int streamNumber,
        @C.TrackType int trackType,
        Format format,
        int descramblingSpan,
        int descramblingPacketSize,
        int descramblingChunkSize) {
      this.streamNumber = streamNumber;
      this.trackType = trackType;
      this.format = format;
      this.descramblingSpan = descramblingSpan;
      this.descramblingPacketSize = descramblingPacketSize;
      this.descramblingChunkSize = descramblingChunkSize;
    }
  }
}
