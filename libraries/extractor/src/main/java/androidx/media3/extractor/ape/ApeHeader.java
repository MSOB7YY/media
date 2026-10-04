// by claude
package androidx.media3.extractor.ape;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;

/**
 * The header of a Monkey's Audio stream and the frame table built from its seek table, everything
 * the decoder and the extractor need. Read with peeks only, the input position stays untouched.
 */
/* package */ final class ApeHeader {

  private static final int MIN_VERSION = 3800;
  private static final int MAX_VERSION = 3990;
  private static final int FIRST_DESCRIPTOR_VERSION = 3980;
  private static final int DESCRIPTOR_SIZE = 52;
  private static final int HEADER_SIZE = 24;
  private static final int OLD_HEADER_SIZE = 32;

  private static final int FLAG_8_BIT = 1;
  private static final int FLAG_HAS_PEAK_LEVEL = 4;
  private static final int FLAG_24_BIT = 8;
  private static final int FLAG_HAS_SEEK_ELEMENTS = 16;
  private static final int FLAG_CREATE_WAV_HEADER = 32;

  private static final int ID3_HEADER_SIZE = 10;

  /** Where each frame is read from and how, built the same way as FFmpeg's demuxer. */
  public static final class Frames {

    /** Frame starts, rounded down to the 32 bit word they start in. */
    public final long[] positions;

    /** Frame sizes from {@link #positions}, whole 32 bit words. */
    public final int[] sizes;

    public final int[] blockCounts;

    /** The bits, or bytes for streams from 3.81 on, the decoder skips at the frame start. */
    public final int[] skips;

    public final int blocksPerFrame;

    private Frames(long[] positions, int[] sizes, int[] blockCounts, int[] skips, int blocksPerFrame) {
      this.positions = positions;
      this.sizes = sizes;
      this.blockCounts = blockCounts;
      this.skips = skips;
      this.blocksPerFrame = blocksPerFrame;
    }
  }

  public final int sampleRate;
  public final int channelCount;
  public final int bitsPerSample;
  public final long totalBlocks;

  /** The file version, compression level and format flags, the extradata FFmpeg's decoder needs. */
  public final byte[] decoderExtraData;

  public final Frames frames;

  private ApeHeader(
      int sampleRate,
      int channelCount,
      int bitsPerSample,
      long totalBlocks,
      byte[] decoderExtraData,
      Frames frames) {
    this.sampleRate = sampleRate;
    this.channelCount = channelCount;
    this.bitsPerSample = bitsPerSample;
    this.totalBlocks = totalBlocks;
    this.decoderExtraData = decoderExtraData;
    this.frames = frames;
  }

  /** Whether {@code input} holds a supported Monkey's Audio stream. */
  public static boolean sniff(ExtractorInput input) throws IOException {
    ParsableByteArray scratch = new ParsableByteArray(ID3_HEADER_SIZE);
    long junkLength = peekId3Length(input, scratch);
    if (junkLength == C.LENGTH_UNSET) {
      return false;
    }
    input.advancePeekPosition((int) junkLength);
    scratch.reset(6);
    if (!input.peekFully(scratch.getData(), 0, 6, /* allowEndOfInput= */ true)) {
      return false;
    }
    if (scratch.readInt() != 0x4D414320) { // "MAC "
      return false;
    }
    int version = scratch.readLittleEndianUnsignedShort();
    return version >= MIN_VERSION && version <= MAX_VERSION;
  }

  /** Parses the header and seek table of the stream {@code input} reads from its start. */
  public static ApeHeader read(ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    ParsableByteArray data = new ParsableByteArray(DESCRIPTOR_SIZE);
    long junkLength = peekId3Length(input, data);
    if (junkLength == C.LENGTH_UNSET) {
      throw ParserException.createForMalformedContainer("Truncated APE stream", null);
    }
    input.advancePeekPosition((int) junkLength);

    data.reset(6);
    input.peekFully(data.getData(), 0, 6);
    if (data.readInt() != 0x4D414320) {
      throw ParserException.createForMalformedContainer("Missing APE signature", null);
    }
    int fileVersion = data.readLittleEndianUnsignedShort();
    if (fileVersion < MIN_VERSION || fileVersion > MAX_VERSION) {
      throw ParserException.createForMalformedContainer("Unsupported APE version " + fileVersion, null);
    }

    int compressionType;
    int formatFlags;
    int blocksPerFrame;
    long finalFrameBlocks;
    long totalFrames;
    int bitsPerSample;
    int channelCount;
    int sampleRate;
    long wavTailLength;
    long seekTableLength;
    // From the start of the "MAC " signature.
    long seekTableOffset;
    long firstFrameOffset;

    if (fileVersion >= FIRST_DESCRIPTOR_VERSION) {
      data.reset(DESCRIPTOR_SIZE - 6);
      input.peekFully(data.getData(), 0, data.limit());
      data.skipBytes(2); // Padding.
      long descriptorLength = data.readLittleEndianUnsignedInt();
      long headerLength = data.readLittleEndianUnsignedInt();
      seekTableLength = data.readLittleEndianUnsignedInt();
      long wavHeaderLength = data.readLittleEndianUnsignedInt();
      data.skipBytes(8); // Audio data length, low and high.
      wavTailLength = data.readLittleEndianUnsignedInt();
      if (descriptorLength < DESCRIPTOR_SIZE || headerLength < HEADER_SIZE) {
        throw ParserException.createForMalformedContainer("Invalid APE descriptor", null);
      }
      input.advancePeekPosition((int) (descriptorLength - DESCRIPTOR_SIZE));

      data.reset(HEADER_SIZE);
      input.peekFully(data.getData(), 0, HEADER_SIZE);
      compressionType = data.readLittleEndianUnsignedShort();
      formatFlags = data.readLittleEndianUnsignedShort();
      blocksPerFrame = data.readLittleEndianInt();
      finalFrameBlocks = data.readLittleEndianUnsignedInt();
      totalFrames = data.readLittleEndianUnsignedInt();
      bitsPerSample = data.readLittleEndianUnsignedShort();
      channelCount = data.readLittleEndianUnsignedShort();
      sampleRate = data.readLittleEndianInt();
      input.advancePeekPosition((int) (headerLength - HEADER_SIZE));

      seekTableOffset = descriptorLength + headerLength;
      firstFrameOffset = seekTableOffset + seekTableLength + wavHeaderLength;
    } else {
      data.reset(OLD_HEADER_SIZE - 6);
      input.peekFully(data.getData(), 0, data.limit());
      compressionType = data.readLittleEndianUnsignedShort();
      formatFlags = data.readLittleEndianUnsignedShort();
      channelCount = data.readLittleEndianUnsignedShort();
      sampleRate = data.readLittleEndianInt();
      long wavHeaderLength = data.readLittleEndianUnsignedInt();
      wavTailLength = data.readLittleEndianUnsignedInt();
      totalFrames = data.readLittleEndianUnsignedInt();
      finalFrameBlocks = data.readLittleEndianUnsignedInt();

      long headerLength = OLD_HEADER_SIZE;
      if ((formatFlags & FLAG_HAS_PEAK_LEVEL) != 0) {
        input.advancePeekPosition(4);
        headerLength += 4;
      }
      if ((formatFlags & FLAG_HAS_SEEK_ELEMENTS) != 0) {
        data.reset(4);
        input.peekFully(data.getData(), 0, 4);
        seekTableLength = data.readLittleEndianUnsignedInt() * 4;
        headerLength += 4;
      } else {
        seekTableLength = totalFrames * 4;
      }

      if ((formatFlags & FLAG_8_BIT) != 0) {
        bitsPerSample = 8;
      } else if ((formatFlags & FLAG_24_BIT) != 0) {
        bitsPerSample = 24;
      } else {
        bitsPerSample = 16;
      }
      if (fileVersion >= 3950) {
        blocksPerFrame = 73728 * 4;
      } else if (fileVersion >= 3900 || compressionType >= 4000) {
        blocksPerFrame = 73728;
      } else {
        blocksPerFrame = 9216;
      }

      boolean isWavHeaderStored = (formatFlags & FLAG_CREATE_WAV_HEADER) == 0;
      seekTableOffset = headerLength + (isWavHeaderStored ? wavHeaderLength : 0);
      firstFrameOffset = headerLength + seekTableLength + wavHeaderLength;
      if (fileVersion < 3810) {
        // The bit table follows the seek table.
        firstFrameOffset += totalFrames;
      }
    }

    if (totalFrames == 0 || totalFrames > Integer.MAX_VALUE / 8 || blocksPerFrame <= 0) {
      throw ParserException.createForMalformedContainer("Invalid APE frame count", null);
    }
    if (seekTableLength / 4 < totalFrames) {
      throw ParserException.createForMalformedContainer("Truncated APE seek table", null);
    }
    if (sampleRate <= 0 || channelCount <= 0) {
      throw ParserException.createForMalformedContainer("Invalid APE stream parameters", null);
    }

    int frameCount = (int) totalFrames;
    input.resetPeekPosition();
    input.advancePeekPosition((int) (junkLength + seekTableOffset));
    // The bit table of streams before 3.81 starts after the declared seek table entries,
    // skipped as bytes, like FFmpeg does.
    int seekTableReadSize = frameCount * 4;
    int bitTableOffset = seekTableReadSize + (int) (seekTableLength / 4 - totalFrames);
    int tableSize = fileVersion < 3810 ? bitTableOffset + frameCount : seekTableReadSize;
    data.reset(tableSize);
    input.peekFully(data.getData(), 0, tableSize);
    input.resetPeekPosition();

    long fileLength = input.getLength();
    Frames frames =
        buildFrames(
            data,
            frameCount,
            fileVersion,
            junkLength,
            junkLength + firstFrameOffset,
            blocksPerFrame,
            (int) finalFrameBlocks,
            fileLength,
            wavTailLength,
            bitTableOffset);

    byte[] extraData = new byte[6];
    writeLittleEndianShort(extraData, 0, fileVersion);
    writeLittleEndianShort(extraData, 2, compressionType);
    writeLittleEndianShort(extraData, 4, formatFlags);
    long totalBlocks = (totalFrames - 1) * blocksPerFrame + finalFrameBlocks;
    return new ApeHeader(sampleRate, channelCount, bitsPerSample, totalBlocks, extraData, frames);
  }

  private static Frames buildFrames(
      ParsableByteArray table,
      int frameCount,
      int fileVersion,
      long junkLength,
      long firstFramePosition,
      int blocksPerFrame,
      int finalFrameBlocks,
      long fileLength,
      long wavTailLength,
      int bitTableOffset)
      throws ParserException {
    long[] positions = new long[frameCount];
    long[] sizes = new long[frameCount];
    int[] blockCounts = new int[frameCount];
    int[] skips = new int[frameCount];

    positions[0] = firstFramePosition;
    blockCounts[0] = blocksPerFrame;
    table.skipBytes(4);
    for (int i = 1; i < frameCount; i++) {
      positions[i] = table.readLittleEndianUnsignedInt() + junkLength;
      blockCounts[i] = blocksPerFrame;
      sizes[i - 1] = positions[i] - positions[i - 1];
      skips[i] = (int) ((positions[i] - positions[0]) & 3);
    }
    blockCounts[frameCount - 1] = finalFrameBlocks;

    long finalSize = 0;
    if (fileLength != C.LENGTH_UNSET) {
      finalSize = fileLength - positions[frameCount - 1] - wavTailLength;
      finalSize -= finalSize & 3;
    }
    if (finalSize <= 0) {
      finalSize = finalFrameBlocks * 8L;
    }
    sizes[frameCount - 1] = finalSize;

    int[] wordSizes = new int[frameCount];
    for (int i = 0; i < frameCount; i++) {
      if (skips[i] != 0) {
        positions[i] -= skips[i];
        sizes[i] += skips[i];
      }
      if (sizes[i] <= 0 || sizes[i] > Integer.MAX_VALUE - 3) {
        throw ParserException.createForMalformedContainer("Invalid APE frame size", null);
      }
      wordSizes[i] = (int) ((sizes[i] + 3) & ~3L);
    }

    if (fileVersion < 3810) {
      table.setPosition(bitTableOffset);
      for (int i = 0; i < frameCount; i++) {
        int bits = table.readUnsignedByte();
        if (i > 0 && bits != 0) {
          wordSizes[i - 1] += 4;
        }
        skips[i] = (skips[i] << 3) + bits;
      }
    }
    return new Frames(positions, wordSizes, blockCounts, skips, blocksPerFrame);
  }

  /** The length of an ID3v2 tag at the peek position, 0 when there's none. */
  private static long peekId3Length(ExtractorInput input, ParsableByteArray scratch)
      throws IOException {
    scratch.reset(ID3_HEADER_SIZE);
    if (!input.peekFully(scratch.getData(), 0, ID3_HEADER_SIZE, /* allowEndOfInput= */ true)) {
      return C.LENGTH_UNSET;
    }
    input.resetPeekPosition();
    if (scratch.readUnsignedInt24() != 0x494433) { // "ID3"
      return 0;
    }
    scratch.skipBytes(2); // Version.
    int flags = scratch.readUnsignedByte();
    int size = scratch.readSynchSafeInt();
    boolean hasFooter = (flags & 0x10) != 0;
    return ID3_HEADER_SIZE + size + (hasFooter ? ID3_HEADER_SIZE : 0);
  }

  private static void writeLittleEndianShort(byte[] data, int offset, int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >> 8);
  }
}
