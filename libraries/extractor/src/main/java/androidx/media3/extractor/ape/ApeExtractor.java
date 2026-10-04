// by claude
package androidx.media3.extractor.ape;

import static androidx.media3.common.util.Assertions.checkStateNotNull;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.math.RoundingMode;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from Monkey's Audio ({@code .ape}) files, ported from FFmpeg's demuxer.
 *
 * <p>Every sample holds one frame the way FFmpeg's decoder takes it: its block count and its bit
 * skip as little endian 32 bit values, then the frame data from the 32 bit word it starts in.
 * Frames don't start on word boundaries, so the last word of a frame is also the first of the next.
 */
@UnstableApi
public final class ApeExtractor implements Extractor {

  /** Factory for {@link ApeExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new ApeExtractor()};

  private static final int FRAME_PREFIX_SIZE = 8;

  private final ParsableByteArray prefixBuffer;
  private final ParsableByteArray tailBuffer;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private ApeHeader.@MonotonicNonNull Frames frames;
  private int sampleRate;
  private int currentFrame;
  private boolean isFramePrefixWritten;

  public ApeExtractor() {
    prefixBuffer = new ParsableByteArray(FRAME_PREFIX_SIZE);
    tailBuffer = new ParsableByteArray(4);
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return ApeHeader.sniff(input);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    trackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    if (frames == null) {
      ApeHeader header = ApeHeader.read(input);
      frames = header.frames;
      outputFormatAndSeekMap(header);
      return RESULT_CONTINUE;
    }
    return readFrame(input, frames);
  }

  @Override
  public void seek(long position, long timeUs) {
    isFramePrefixWritten = false;
    if (frames == null) {
      currentFrame = 0;
      return;
    }
    currentFrame =
        Util.binarySearchFloor(
            frames.positions, position, /* inclusive= */ true, /* stayInBounds= */ true);
  }

  @Override
  public void release() {
    // Do nothing.
  }

  private void outputFormatAndSeekMap(ApeHeader header) {
    ApeHeader.Frames frames = header.frames;
    sampleRate = header.sampleRate;
    long durationUs =
        Util.scaleLargeTimestamp(header.totalBlocks, C.MICROS_PER_SECOND, sampleRate);
    int frameCount = frames.positions.length;
    long[] timesUs = new long[frameCount];
    int maxFrameSize = 0;
    long dataSize = 0;
    for (int i = 0; i < frameCount; i++) {
      timesUs[i] = getFrameTimeUs(i);
      maxFrameSize = Math.max(maxFrameSize, frames.sizes[i]);
      dataSize += frames.sizes[i];
    }

    Format.Builder format =
        new Format.Builder()
            .setContainerMimeType(MimeTypes.AUDIO_APE)
            .setSampleMimeType(MimeTypes.AUDIO_APE)
            .setSampleRate(sampleRate)
            .setChannelCount(header.channelCount)
            .setPcmEncoding(Util.getPcmEncoding(header.bitsPerSample))
            .setMaxInputSize(FRAME_PREFIX_SIZE + maxFrameSize)
            .setInitializationData(ImmutableList.of(header.decoderExtraData));
    if (durationUs > 0) {
      long bitrate =
          Util.scaleLargeValue(dataSize, 8 * C.MICROS_PER_SECOND, durationUs, RoundingMode.HALF_UP);
      format.setAverageBitrate((int) Math.min(bitrate, Integer.MAX_VALUE));
    }
    checkStateNotNull(trackOutput).format(format.build());
    checkStateNotNull(extractorOutput)
        .seekMap(new IndexSeekMap(frames.positions, timesUs, durationUs));
  }

  private long getFrameTimeUs(int frameIndex) {
    long firstBlock = (long) frameIndex * checkStateNotNull(frames).blocksPerFrame;
    return Util.scaleLargeTimestamp(firstBlock, C.MICROS_PER_SECOND, sampleRate);
  }

  /**
   * Writes the current frame. Progress is tracked by input position, so a load that's retried
   * mid-frame resumes where it stopped.
   */
  private @ReadResult int readFrame(ExtractorInput input, ApeHeader.Frames frames)
      throws IOException {
    int frameCount = frames.positions.length;
    if (currentFrame >= frameCount) {
      return RESULT_END_OF_INPUT;
    }
    TrackOutput trackOutput = checkStateNotNull(this.trackOutput);
    long frameStart = frames.positions[currentFrame];
    long position = input.getPosition();
    if (position < frameStart) {
      int skipped = input.skip((int) Math.min(frameStart - position, Integer.MAX_VALUE));
      return skipped == C.RESULT_END_OF_INPUT ? RESULT_END_OF_INPUT : RESULT_CONTINUE;
    }

    if (!isFramePrefixWritten) {
      prefixBuffer.reset(FRAME_PREFIX_SIZE);
      byte[] prefix = prefixBuffer.getData();
      writeLittleEndianInt(prefix, 0, frames.blockCounts[currentFrame]);
      writeLittleEndianInt(prefix, 4, frames.skips[currentFrame]);
      trackOutput.sampleData(prefixBuffer, FRAME_PREFIX_SIZE);
      isFramePrefixWritten = true;
    }

    int frameSize = frames.sizes[currentFrame];
    long frameEnd = frameStart + frameSize;
    // The overlap with the next frame is only peeked, the next frame reads it again.
    long ownedEnd =
        currentFrame + 1 < frameCount
            ? Math.min(frameEnd, frames.positions[currentFrame + 1])
            : frameEnd;
    if (position < ownedEnd) {
      int remaining = (int) (ownedEnd - position);
      int written = trackOutput.sampleData(input, remaining, /* allowEndOfInput= */ true);
      if (written == C.RESULT_END_OF_INPUT) {
        // The last frame's size is estimated, a few bytes may be missing.
        commitFrame(trackOutput, (int) (position - frameStart));
        return RESULT_END_OF_INPUT;
      }
      return RESULT_CONTINUE;
    }

    int tailSize = (int) (frameEnd - ownedEnd);
    int writtenSize = (int) (ownedEnd - frameStart);
    if (tailSize > 0) {
      tailBuffer.reset(tailSize);
      if (input.peekFully(tailBuffer.getData(), 0, tailSize, /* allowEndOfInput= */ true)) {
        trackOutput.sampleData(tailBuffer, tailSize);
        writtenSize += tailSize;
      }
      input.resetPeekPosition();
    }
    commitFrame(trackOutput, writtenSize);
    return RESULT_CONTINUE;
  }

  private void commitFrame(TrackOutput trackOutput, int frameDataSize) {
    trackOutput.sampleMetadata(
        getFrameTimeUs(currentFrame),
        C.BUFFER_FLAG_KEY_FRAME,
        FRAME_PREFIX_SIZE + frameDataSize,
        /* offset= */ 0,
        /* cryptoData= */ null);
    currentFrame++;
    isFramePrefixWritten = false;
  }

  private static void writeLittleEndianInt(byte[] data, int offset, int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >> 8);
    data[offset + 2] = (byte) (value >> 16);
    data[offset + 3] = (byte) (value >> 24);
  }
}
