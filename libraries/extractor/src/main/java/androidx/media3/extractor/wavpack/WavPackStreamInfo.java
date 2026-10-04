// by claude
package androidx.media3.extractor.wavpack;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;

/**
 * The stream parameters of a WavPack file, from the flags and metadata of its first audio block,
 * resolved the same way as FFmpeg's demuxer.
 */
/* package */ final class WavPackStreamInfo {

  private static final int[] SAMPLE_RATES = {
    6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000,
    192000, C.RATE_UNSET_INT
  };
  private static final int SAMPLE_RATE_SHIFT = 23;
  private static final int BYTES_STORED_MASK = 0x3;

  private static final int METADATA_ID_MASK = 0x3F;
  private static final int METADATA_ODD_SIZE = 0x40;
  private static final int METADATA_LARGE = 0x80;
  private static final int METADATA_CHANNEL_INFO = 0xD;
  private static final int METADATA_DSD_BLOCK = 0xE;
  private static final int METADATA_SAMPLE_RATE = 0x27;

  public final int sampleRate;
  public final int channelCount;

  /** The encoding the samples were stored in, {@link Format#NO_VALUE} for DSD. */
  public final int pcmEncoding;

  private WavPackStreamInfo(int sampleRate, int channelCount, int pcmEncoding) {
    this.sampleRate = sampleRate;
    this.channelCount = channelCount;
    this.pcmEncoding = pcmEncoding;
  }

  /**
   * Returns the parameters of the stream, or null if they're invalid.
   *
   * @param header The header of the first block holding samples.
   * @param blockData The block after its header.
   */
  @Nullable
  public static WavPackStreamInfo parse(WavPackBlockHeader header, ParsableByteArray blockData) {
    int flags = header.flags;
    boolean isDsd = (flags & WavPackBlockHeader.FLAG_DSD) != 0;
    int sampleRate = SAMPLE_RATES[(flags >>> SAMPLE_RATE_SHIFT) & 0xF];
    int rateMultiplier = isDsd ? 4 : 1;
    int channelCount = (flags & WavPackBlockHeader.FLAG_MONO) != 0 ? 1 : 2;
    boolean isMultichannel = !header.isFinalBlock();
    boolean hasChannelInfo = false;

    while (blockData.bytesLeft() >= 2) {
      int id = blockData.readUnsignedByte();
      int size =
          (id & METADATA_LARGE) != 0
              ? blockData.readLittleEndianUnsignedInt24()
              : blockData.readUnsignedByte();
      size <<= 1;
      if ((id & METADATA_ODD_SIZE) != 0) {
        size--;
      }
      if (size < 0 || size > blockData.bytesLeft()) {
        return null;
      }
      int next = blockData.getPosition() + size + ((id & METADATA_ODD_SIZE) != 0 ? 1 : 0);
      switch (id & METADATA_ID_MASK) {
        case METADATA_CHANNEL_INFO:
          if (size >= 1) {
            channelCount = parseChannelCount(blockData, size);
            hasChannelInfo = true;
          }
          break;
        case METADATA_DSD_BLOCK:
          if (size >= 1) {
            rateMultiplier = 1 << (blockData.readUnsignedByte() & 0x1F);
          }
          break;
        case METADATA_SAMPLE_RATE:
          if (size >= 3) {
            sampleRate = blockData.readLittleEndianUnsignedInt24();
          }
          break;
        default:
          break;
      }
      blockData.setPosition(Math.min(next, blockData.limit()));
    }

    if (isMultichannel && !hasChannelInfo) {
      return null;
    }
    if (sampleRate == C.RATE_UNSET_INT || sampleRate <= 0 || channelCount <= 0) {
      return null;
    }
    long totalRate = (long) sampleRate * rateMultiplier;
    if (totalRate >= Integer.MAX_VALUE) {
      return null;
    }

    int pcmEncoding;
    if (isDsd) {
      pcmEncoding = Format.NO_VALUE;
    } else if ((flags & WavPackBlockHeader.FLAG_FLOAT) != 0) {
      pcmEncoding = C.ENCODING_PCM_FLOAT;
    } else {
      int bitDepth = ((flags & BYTES_STORED_MASK) + 1) * 8;
      pcmEncoding = Util.getPcmEncoding(bitDepth);
    }
    return new WavPackStreamInfo((int) totalRate, channelCount, pcmEncoding);
  }

  /** The channel count from an ID_CHANNEL_INFO metadata of {@code size} bytes. */
  private static int parseChannelCount(ParsableByteArray data, int size) {
    int channelCount = data.readUnsignedByte();
    // 12 bit channel counts come with 3 or 4 byte channel masks.
    if (size == 6 || size == 7) {
      data.skipBytes(1);
      channelCount |= (data.readUnsignedByte() & 0xF) << 8;
      channelCount++;
    }
    return channelCount;
  }
}
