// by claude
package androidx.media3.extractor.wavpack;

import static androidx.media3.common.util.Assertions.checkStateNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.math.RoundingMode;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from WavPack ({@code .wv}) files.
 *
 * <p>Every sample holds one frame: the blocks from an initial block to a final block (one block
 * per channel pair), headers included, which is what FFmpeg's decoder takes. Blocks without
 * samples only carry metadata and are skipped.
 */
@UnstableApi
public final class WavPackExtractor implements Extractor {

  /** Factory for {@link WavPackExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new WavPackExtractor()};

  private static final int MAX_LEADING_EMPTY_BLOCKS = 16;

  private final ParsableByteArray headerBuffer;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  @Nullable private WavPackBinarySearchSeeker binarySearchSeeker;
  private boolean hasOutputFormat;
  private int sampleRate;
  private long firstBlockIndex;

  private long blockEndPosition;
  private boolean isSkippingBlock;
  private boolean isFinalBlock;
  private int frameBytesWritten;
  private long frameTimeUs;

  public WavPackExtractor() {
    headerBuffer = new ParsableByteArray(WavPackBlockHeader.SIZE);
    blockEndPosition = C.INDEX_UNSET;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return peekHeader(input, /* allowEndOfInput= */ true) != null;
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
    if (!hasOutputFormat) {
      outputFormatAndSeekMap(input);
      hasOutputFormat = true;
      return RESULT_CONTINUE;
    }
    if (binarySearchSeeker != null && binarySearchSeeker.isSeeking()) {
      return binarySearchSeeker.handlePendingSeek(input, seekPosition);
    }
    return readBlock(input);
  }

  @Override
  public void seek(long position, long timeUs) {
    blockEndPosition = C.INDEX_UNSET;
    frameBytesWritten = 0;
    if (position != 0 && binarySearchSeeker != null) {
      binarySearchSeeker.setSeekTargetUs(timeUs);
    }
  }

  @Override
  public void release() {
    // Do nothing.
  }

  /** Peeks and parses the block header at the peek position, null when there's none. */
  @Nullable
  private WavPackBlockHeader peekHeader(ExtractorInput input, boolean allowEndOfInput)
      throws IOException {
    if (!input.peekFully(headerBuffer.getData(), 0, WavPackBlockHeader.SIZE, allowEndOfInput)) {
      return null;
    }
    headerBuffer.setPosition(0);
    return WavPackBlockHeader.parse(headerBuffer);
  }

  /**
   * Takes the stream parameters from the first block holding samples, like FFmpeg does, without
   * consuming any input.
   */
  private void outputFormatAndSeekMap(ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    long firstBlockPosition = input.getPosition();
    @Nullable WavPackBlockHeader header = null;
    for (int i = 0; i < MAX_LEADING_EMPTY_BLOCKS && header == null; i++) {
      header = peekHeader(input, /* allowEndOfInput= */ false);
      if (header == null) {
        throw ParserException.createForMalformedContainer("Invalid WavPack block header", null);
      }
      if (header.blockSamples == 0 || !header.isInitialBlock()) {
        input.advancePeekPosition(header.blockSize - WavPackBlockHeader.SIZE);
        firstBlockPosition += header.blockSize;
        header = null;
      }
    }
    if (header == null) {
      throw ParserException.createForMalformedContainer("No WavPack audio block", null);
    }

    ParsableByteArray blockData = new ParsableByteArray(header.blockSize - WavPackBlockHeader.SIZE);
    input.peekFully(blockData.getData(), 0, blockData.limit());
    input.resetPeekPosition();
    @Nullable WavPackStreamInfo streamInfo = WavPackStreamInfo.parse(header, blockData);
    if (streamInfo == null) {
      throw ParserException.createForMalformedContainer("Unsupported WavPack stream", null);
    }

    sampleRate = streamInfo.sampleRate;
    firstBlockIndex = header.blockIndex;
    long totalSamples = header.totalSamples;
    long durationUs =
        totalSamples == C.LENGTH_UNSET
            ? C.TIME_UNSET
            : Util.scaleLargeTimestamp(totalSamples, C.MICROS_PER_SECOND, sampleRate);
    long inputLength = input.getLength();
    boolean isSeekable = durationUs > 0 && inputLength != C.LENGTH_UNSET;

    Format.Builder format =
        new Format.Builder()
            .setContainerMimeType(MimeTypes.AUDIO_WAVPACK)
            .setSampleMimeType(MimeTypes.AUDIO_WAVPACK)
            .setSampleRate(sampleRate)
            .setChannelCount(streamInfo.channelCount)
            .setPcmEncoding(streamInfo.pcmEncoding);
    if (isSeekable) {
      long bitrate =
          Util.scaleLargeValue(
              inputLength - firstBlockPosition,
              8 * C.MICROS_PER_SECOND,
              durationUs,
              RoundingMode.HALF_UP);
      format.setAverageBitrate((int) Math.min(bitrate, Integer.MAX_VALUE));
    }
    checkStateNotNull(trackOutput).format(format.build());

    SeekMap seekMap;
    if (isSeekable) {
      binarySearchSeeker =
          new WavPackBinarySearchSeeker(
              firstBlockIndex,
              totalSamples,
              header.blockSamples,
              sampleRate,
              firstBlockPosition,
              inputLength);
      seekMap = binarySearchSeeker.getSeekMap();
    } else {
      seekMap = new SeekMap.Unseekable(durationUs);
    }
    checkStateNotNull(extractorOutput).seekMap(seekMap);
  }

  /** Writes the current block, the frame's sample is committed once its final block is in. */
  private @ReadResult int readBlock(ExtractorInput input) throws IOException {
    if (blockEndPosition == C.INDEX_UNSET) {
      @Nullable WavPackBlockHeader header = peekHeader(input, /* allowEndOfInput= */ true);
      input.resetPeekPosition();
      if (header == null) {
        // Tags (APEv2, ID3v1) follow the last block.
        return RESULT_END_OF_INPUT;
      }
      boolean isFrameStart = frameBytesWritten == 0;
      blockEndPosition = input.getPosition() + header.blockSize;
      isSkippingBlock = header.blockSamples == 0 || (isFrameStart && !header.isInitialBlock());
      isFinalBlock = header.isFinalBlock();
      if (isFrameStart && !isSkippingBlock) {
        frameTimeUs =
            Util.scaleLargeTimestamp(
                header.blockIndex - firstBlockIndex, C.MICROS_PER_SECOND, sampleRate);
      }
    }

    // Positions rather than counters, so a load that's retried mid-block resumes where it stopped.
    int remaining = (int) (blockEndPosition - input.getPosition());
    if (remaining > 0) {
      int handled =
          isSkippingBlock
              ? input.skip(remaining)
              : checkStateNotNull(trackOutput).sampleData(input, remaining, true);
      if (handled == C.RESULT_END_OF_INPUT) {
        return RESULT_END_OF_INPUT;
      }
      if (!isSkippingBlock) {
        frameBytesWritten += handled;
      }
      if (handled < remaining) {
        return RESULT_CONTINUE;
      }
    }

    blockEndPosition = C.INDEX_UNSET;
    if (!isSkippingBlock && isFinalBlock) {
      checkStateNotNull(trackOutput)
          .sampleMetadata(frameTimeUs, C.BUFFER_FLAG_KEY_FRAME, frameBytesWritten, 0, null);
      frameBytesWritten = 0;
    }
    return RESULT_CONTINUE;
  }
}
