// by claude
package androidx.media3.exoplayer.audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;

/**
 * An {@link AudioProcessor} that moves PCM samples into a wider integer container without changing
 * their value, for bit-perfect outputs that only accept some encodings.
 *
 * <p>Float input is only lossless when its samples originate from integers no wider than the output
 * encoding, which is the case for decoders that output float PCM.
 */
/* package */ final class BitPerfectPcmAudioProcessor extends BaseAudioProcessor {

  private @C.PcmEncoding int outputEncoding;

  public BitPerfectPcmAudioProcessor() {
    outputEncoding = C.ENCODING_INVALID;
  }

  /** Sets the encoding to output, applied by the next call to {@link #configure(AudioFormat)}. */
  public void setOutputEncoding(@C.PcmEncoding int outputEncoding) {
    this.outputEncoding = outputEncoding;
  }

  @Override
  protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    @C.PcmEncoding int inputEncoding = inputAudioFormat.encoding;
    if (inputEncoding == outputEncoding) {
      return AudioFormat.NOT_SET;
    }
    if (!canHoldLosslessly(inputEncoding, outputEncoding)) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    return new AudioFormat(
        inputAudioFormat.sampleRate, inputAudioFormat.channelCount, outputEncoding);
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    int position = inputBuffer.position();
    int limit = inputBuffer.limit();
    @C.PcmEncoding int inputEncoding = inputAudioFormat.encoding;
    @C.PcmEncoding int encoding = outputAudioFormat.encoding;
    int inputBytesPerSample = getBytesPerSample(inputEncoding);
    int sampleCount = (limit - position) / inputBytesPerSample;
    ByteBuffer buffer = replaceOutputBuffer(sampleCount * getBytesPerSample(encoding));

    if (inputEncoding == C.ENCODING_PCM_FLOAT) {
      double scale = getFullScale(encoding);
      double max = scale - 1;
      for (int i = position; i < limit; i += 4) {
        double sample = Math.rint(inputBuffer.getFloat(i) * scale);
        writeSample(buffer, (int) Math.max(-scale, Math.min(max, sample)), encoding);
      }
    } else {
      int shift = (getBytesPerSample(encoding) - inputBytesPerSample) * 8;
      for (int i = position; i < limit; i += inputBytesPerSample) {
        writeSample(buffer, readSample(inputBuffer, i, inputEncoding) << shift, encoding);
      }
    }

    inputBuffer.position(limit);
    buffer.flip();
  }

  private static boolean canHoldLosslessly(
      @C.PcmEncoding int inputEncoding, @C.PcmEncoding int outputEncoding) {
    switch (inputEncoding) {
      case C.ENCODING_PCM_16BIT:
        return outputEncoding == C.ENCODING_PCM_24BIT || outputEncoding == C.ENCODING_PCM_32BIT;
      case C.ENCODING_PCM_24BIT:
        return outputEncoding == C.ENCODING_PCM_32BIT;
      case C.ENCODING_PCM_FLOAT:
        return outputEncoding == C.ENCODING_PCM_16BIT
            || outputEncoding == C.ENCODING_PCM_24BIT
            || outputEncoding == C.ENCODING_PCM_32BIT;
      default:
        return false;
    }
  }

  private static int getBytesPerSample(@C.PcmEncoding int encoding) {
    switch (encoding) {
      case C.ENCODING_PCM_16BIT:
        return 2;
      case C.ENCODING_PCM_24BIT:
        return 3;
      default:
        return 4;
    }
  }

  private static double getFullScale(@C.PcmEncoding int encoding) {
    switch (encoding) {
      case C.ENCODING_PCM_16BIT:
        return 0x1p15;
      case C.ENCODING_PCM_24BIT:
        return 0x1p23;
      default:
        return 0x1p31;
    }
  }

  private static int readSample(ByteBuffer buffer, int index, @C.PcmEncoding int encoding) {
    switch (encoding) {
      case C.ENCODING_PCM_16BIT:
        return (buffer.get(index) & 0xFF) | (buffer.get(index + 1) << 8);
      case C.ENCODING_PCM_24BIT:
        return (buffer.get(index) & 0xFF)
            | ((buffer.get(index + 1) & 0xFF) << 8)
            | (buffer.get(index + 2) << 16);
      default:
        return (buffer.get(index) & 0xFF)
            | ((buffer.get(index + 1) & 0xFF) << 8)
            | ((buffer.get(index + 2) & 0xFF) << 16)
            | (buffer.get(index + 3) << 24);
    }
  }

  private static void writeSample(ByteBuffer buffer, int sample, @C.PcmEncoding int encoding) {
    buffer.put((byte) sample);
    buffer.put((byte) (sample >> 8));
    if (encoding == C.ENCODING_PCM_16BIT) {
      return;
    }
    buffer.put((byte) (sample >> 16));
    if (encoding == C.ENCODING_PCM_24BIT) {
      return;
    }
    buffer.put((byte) (sample >> 24));
  }
}
