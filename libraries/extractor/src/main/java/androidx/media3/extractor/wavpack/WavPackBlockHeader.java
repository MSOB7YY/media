// by claude
package androidx.media3.extractor.wavpack;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;

/** The 32 byte header every WavPack block starts with. */
/* package */ final class WavPackBlockHeader {

  public static final int SIZE = 32;

  public static final int FLAG_MONO = 0x4;
  public static final int FLAG_FLOAT = 0x80;
  public static final int FLAG_INITIAL_BLOCK = 0x800;
  public static final int FLAG_FINAL_BLOCK = 0x1000;
  public static final int FLAG_DSD = 0x80000000;

  private static final int MIN_VERSION = 0x402;
  private static final int MAX_VERSION = 0x410;
  private static final int MAX_BLOCK_SIZE = 1 << 20;
  private static final int MAX_BLOCK_SAMPLES = 150_000;
  private static final long UNKNOWN_TOTAL_SAMPLES = 0xFFFFFFFFL;

  /** The whole block, header included. */
  public final int blockSize;

  /** The first sample of the block, in samples per channel from the start of the stream. */
  public final long blockIndex;

  /** The samples per channel of the stream, or {@link C#LENGTH_UNSET} when unknown. */
  public final long totalSamples;

  public final int blockSamples;
  public final int flags;

  private WavPackBlockHeader(
      int blockSize, long blockIndex, long totalSamples, int blockSamples, int flags) {
    this.blockSize = blockSize;
    this.blockIndex = blockIndex;
    this.totalSamples = totalSamples;
    this.blockSamples = blockSamples;
    this.flags = flags;
  }

  /**
   * Parses the header at the position of {@code data}, which must hold {@link #SIZE} bytes, or
   * returns null if it isn't a valid one. The checks are tight enough to tell a header apart from
   * audio data, which seeking relies on.
   */
  @Nullable
  public static WavPackBlockHeader parse(ParsableByteArray data) {
    int start = data.getPosition();
    byte[] bytes = data.getData();
    if (bytes[start] != 'w'
        || bytes[start + 1] != 'v'
        || bytes[start + 2] != 'p'
        || bytes[start + 3] != 'k') {
      return null;
    }
    data.skipBytes(4);
    long chunkSize = data.readLittleEndianUnsignedInt();
    int version = data.readLittleEndianUnsignedShort();
    // WavPack 5 widens the block index and total samples to 40 bits.
    int blockIndexHigh = data.readUnsignedByte();
    int totalSamplesHigh = data.readUnsignedByte();
    long totalSamplesLow = data.readLittleEndianUnsignedInt();
    long blockIndexLow = data.readLittleEndianUnsignedInt();
    long blockSamples = data.readLittleEndianUnsignedInt();
    int flags = data.readLittleEndianInt();
    data.skipBytes(4); // CRC.

    if (chunkSize < SIZE - 8 || chunkSize > MAX_BLOCK_SIZE) {
      return null;
    }
    if (version < MIN_VERSION || version > MAX_VERSION || blockSamples > MAX_BLOCK_SAMPLES) {
      return null;
    }
    long totalSamples =
        totalSamplesLow == UNKNOWN_TOTAL_SAMPLES
            ? C.LENGTH_UNSET
            : ((long) totalSamplesHigh << 32) | totalSamplesLow;
    long blockIndex = ((long) blockIndexHigh << 32) | blockIndexLow;
    return new WavPackBlockHeader(
        (int) chunkSize + 8, blockIndex, totalSamples, (int) blockSamples, flags);
  }

  public boolean isInitialBlock() {
    return (flags & FLAG_INITIAL_BLOCK) != 0;
  }

  public boolean isFinalBlock() {
    return (flags & FLAG_FINAL_BLOCK) != 0;
  }
}
