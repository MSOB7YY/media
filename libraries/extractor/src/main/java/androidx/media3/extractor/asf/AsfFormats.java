// by claude
package androidx.media3.extractor.asf;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Formats of ASF streams, from the WAVEFORMATEX or BITMAPINFOHEADER of their stream properties. */
/* package */ final class AsfFormats {

  private static final int WAVE_FORMAT_PCM = 0x0001;
  private static final int WAVE_FORMAT_IEEE_FLOAT = 0x0003;
  private static final int WAVE_FORMAT_MPEG = 0x0050;
  private static final int WAVE_FORMAT_MPEG_LAYER_3 = 0x0055;
  private static final int WAVE_FORMAT_WMAV1 = 0x0160;
  private static final int WAVE_FORMAT_WMAV2 = 0x0161;
  private static final int WAVE_FORMAT_WMAPRO = 0x0162;
  private static final int WAVE_FORMAT_WMA_LOSSLESS = 0x0163;
  private static final int WAVE_FORMAT_DOLBY_AC3 = 0x2000;
  private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

  private static final int WAVE_FORMAT_SIZE = 14;
  private static final int WAVE_FORMAT_EX_SIZE = 18;
  private static final int WAVE_FORMAT_EXTENSIBLE_EXTRA_SIZE = 22;
  private static final int SUB_FORMAT_OFFSET = 24;

  /** Encoded image width and height, a reserved byte and the format data size come first. */
  private static final int VIDEO_INFO_SIZE = 11;

  private static final int BITMAP_INFO_HEADER_SIZE = 40;
  private static final int COMPRESSION_OFFSET = VIDEO_INFO_SIZE + 16;

  private AsfFormats() {}

  /**
   * Returns the format of an audio stream from its WAVEFORMATEX, or null when the codec isn't one
   * that can be played.
   *
   * <p>WMA streams keep the whole WAVEFORMATEX as their initialization data, the decoder needs its
   * block alignment and bitrate next to the codec data that follows it.
   */
  @Nullable
  public static Format.Builder getAudioFormat(byte[] waveFormat) {
    int length = waveFormat.length;
    if (length < WAVE_FORMAT_SIZE) {
      return null;
    }
    int formatTag = readUnsignedShort(waveFormat, 0);
    int channelCount = readUnsignedShort(waveFormat, 2);
    int sampleRate = readInt(waveFormat, 4);
    int averageBytesPerSecond = readInt(waveFormat, 8);
    int bitsPerSample = length >= WAVE_FORMAT_SIZE + 2 ? readUnsignedShort(waveFormat, 14) : 8;
    int extraDataOffset = WAVE_FORMAT_EX_SIZE;
    int extraDataSize = 0;
    if (length >= WAVE_FORMAT_EX_SIZE) {
      int cbSize = readUnsignedShort(waveFormat, 16);
      extraDataSize = Math.min(cbSize, length - WAVE_FORMAT_EX_SIZE);
    }
    if (formatTag == WAVE_FORMAT_EXTENSIBLE && extraDataSize >= WAVE_FORMAT_EXTENSIBLE_EXTRA_SIZE) {
      // The sub format GUIDs of the standard codecs start with their format tag.
      formatTag = readUnsignedShort(waveFormat, SUB_FORMAT_OFFSET);
      extraDataOffset += WAVE_FORMAT_EXTENSIBLE_EXTRA_SIZE;
      extraDataSize -= WAVE_FORMAT_EXTENSIBLE_EXTRA_SIZE;
    }

    String mimeType;
    @C.PcmEncoding int pcmEncoding = Format.NO_VALUE;
    boolean needsWaveFormat = false;
    switch (formatTag) {
      case WAVE_FORMAT_PCM:
        pcmEncoding = Util.getPcmEncoding(bitsPerSample);
        if (pcmEncoding == C.ENCODING_INVALID) {
          return null;
        }
        mimeType = MimeTypes.AUDIO_RAW;
        break;
      case WAVE_FORMAT_IEEE_FLOAT:
        if (bitsPerSample != 32) {
          return null;
        }
        pcmEncoding = C.ENCODING_PCM_FLOAT;
        mimeType = MimeTypes.AUDIO_RAW;
        break;
      case WAVE_FORMAT_MPEG:
        mimeType = MimeTypes.AUDIO_MPEG_L2;
        break;
      case WAVE_FORMAT_MPEG_LAYER_3:
        mimeType = MimeTypes.AUDIO_MPEG;
        break;
      case WAVE_FORMAT_DOLBY_AC3:
        mimeType = MimeTypes.AUDIO_AC3;
        break;
      case WAVE_FORMAT_WMAV1:
        mimeType = MimeTypes.AUDIO_WMAV1;
        needsWaveFormat = true;
        break;
      case WAVE_FORMAT_WMAV2:
        mimeType = MimeTypes.AUDIO_WMAV2;
        needsWaveFormat = true;
        break;
      case WAVE_FORMAT_WMAPRO:
        mimeType = MimeTypes.AUDIO_WMAPRO;
        needsWaveFormat = true;
        break;
      case WAVE_FORMAT_WMA_LOSSLESS:
        mimeType = MimeTypes.AUDIO_WMA_LOSSLESS;
        needsWaveFormat = true;
        break;
      default:
        return null;
    }

    Format.Builder format =
        new Format.Builder()
            .setSampleMimeType(mimeType)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRate)
            .setPcmEncoding(pcmEncoding);
    if (averageBytesPerSecond > 0 && averageBytesPerSecond <= Integer.MAX_VALUE / 8) {
      format.setAverageBitrate(averageBytesPerSecond * 8);
    }
    if (needsWaveFormat) {
      byte[] waveFormatEx =
          toWaveFormatEx(waveFormat, formatTag, extraDataOffset, extraDataSize);
      format.setInitializationData(ImmutableList.of(waveFormatEx));
    }
    return format;
  }

  /**
   * Returns the format of a video stream from its video info and BITMAPINFOHEADER, or null when the
   * codec isn't one that can be played. The bytes after the BITMAPINFOHEADER are the codec data.
   */
  @Nullable
  public static Format.Builder getVideoFormat(byte[] typeSpecificData) {
    int length = typeSpecificData.length;
    int bitmapInfoEnd = VIDEO_INFO_SIZE + BITMAP_INFO_HEADER_SIZE;
    if (length < bitmapInfoEnd) {
      return null;
    }
    String compression =
        new String(typeSpecificData, COMPRESSION_OFFSET, 4, StandardCharsets.US_ASCII);
    String fourCc = compression.toUpperCase(Locale.US);
    @Nullable String mimeType = getVideoMimeType(fourCc);
    if (mimeType == null) {
      return null;
    }
    int bitmapInfoSize = readInt(typeSpecificData, VIDEO_INFO_SIZE);
    int width = readInt(typeSpecificData, VIDEO_INFO_SIZE + 4);
    int height = readInt(typeSpecificData, VIDEO_INFO_SIZE + 8);
    Format.Builder format =
        new Format.Builder()
            .setSampleMimeType(mimeType)
            .setWidth(width)
            .setHeight(Math.abs(height));
    int codecDataSize = Math.min(bitmapInfoSize - BITMAP_INFO_HEADER_SIZE, length - bitmapInfoEnd);
    int codecDataStart = bitmapInfoEnd;
    if (mimeType.equals(MimeTypes.VIDEO_VC1)) {
      codecDataStart = findVc1SequenceHeader(typeSpecificData, bitmapInfoEnd, codecDataSize);
      codecDataSize -= codecDataStart - bitmapInfoEnd;
    }
    if (codecDataSize > 0) {
      byte[] codecData =
          Arrays.copyOfRange(typeSpecificData, codecDataStart, codecDataStart + codecDataSize);
      format.setInitializationData(ImmutableList.of(codecData));
    }
    return format;
  }

  /**
   * Returns where the VC-1 sequence header starts in the codec data, which is what MediaCodec takes
   * (the same as Matroska's), or {@code offset} when there's none.
   */
  private static int findVc1SequenceHeader(byte[] data, int offset, int length) {
    for (int i = offset; i < offset + length - 3; i++) {
      if (data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x01 && data[i + 3] == 0x0F) {
        return i;
      }
    }
    return offset;
  }

  @Nullable
  private static String getVideoMimeType(String compression) {
    switch (compression) {
      case "WMV1":
        return MimeTypes.VIDEO_WMV1;
      case "WMV2":
        return MimeTypes.VIDEO_WMV2;
      case "WMV3":
        return MimeTypes.VIDEO_WMV3;
      case "WVC1":
      case "WMVA":
        return MimeTypes.VIDEO_VC1;
      case "MP42":
        return MimeTypes.VIDEO_MP42;
      case "MP43":
      case "DIV3":
      case "MPG3":
        return MimeTypes.VIDEO_MP43;
      case "MP4S":
      case "M4S2":
      case "MP4V":
      case "XVID":
      case "DIVX":
      case "DX50":
      case "FMP4":
        return MimeTypes.VIDEO_MP4V;
      case "H264":
      case "X264":
      case "AVC1":
        return MimeTypes.VIDEO_H264;
      case "MJPG":
        return MimeTypes.VIDEO_MJPEG;
      default:
        return null;
    }
  }

  /** A WAVEFORMATEX with {@code formatTag}, its codec data being the extensible one's when needed. */
  private static byte[] toWaveFormatEx(
      byte[] waveFormat, int formatTag, int extraDataOffset, int extraDataSize) {
    byte[] waveFormatEx = new byte[WAVE_FORMAT_EX_SIZE + extraDataSize];
    int headerSize = Math.min(waveFormat.length, WAVE_FORMAT_EX_SIZE - 2);
    System.arraycopy(waveFormat, 0, waveFormatEx, 0, headerSize);
    writeUnsignedShort(waveFormatEx, 0, formatTag);
    writeUnsignedShort(waveFormatEx, WAVE_FORMAT_EX_SIZE - 2, extraDataSize);
    System.arraycopy(
        waveFormat, extraDataOffset, waveFormatEx, WAVE_FORMAT_EX_SIZE, extraDataSize);
    return waveFormatEx;
  }

  private static int readUnsignedShort(byte[] data, int offset) {
    return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
  }

  private static int readInt(byte[] data, int offset) {
    return (data[offset] & 0xFF)
        | (data[offset + 1] & 0xFF) << 8
        | (data[offset + 2] & 0xFF) << 16
        | (data[offset + 3] & 0xFF) << 24;
  }

  private static void writeUnsignedShort(byte[] data, int offset, int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >> 8);
  }
}
