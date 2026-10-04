// by claude
package androidx.media3.extractor.wavpack;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.BinarySearchSeeker;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;
import java.math.RoundingMode;

/**
 * A {@link BinarySearchSeeker} for WavPack streams, which carry no index. Timestamps are block
 * indices, in samples per channel.
 */
/* package */ final class WavPackBinarySearchSeeker extends BinarySearchSeeker {

  private static final int MIN_SEARCH_RANGE = WavPackBlockHeader.SIZE;

  /**
   * @param firstBlockIndex The block index of the first audio block.
   * @param totalSamples The samples per channel of the stream.
   * @param blockSamples The samples per channel of the first audio block, typical for the stream.
   * @param sampleRate The sample rate the block indices count in.
   * @param firstBlockPosition The byte offset of the first audio block.
   * @param inputLength The length of the stream in bytes.
   */
  public WavPackBinarySearchSeeker(
      long firstBlockIndex,
      long totalSamples,
      int blockSamples,
      int sampleRate,
      long firstBlockPosition,
      long inputLength) {
    super(
        /* seekTimestampConverter= */ timeUs ->
            firstBlockIndex + Util.scaleLargeTimestamp(timeUs, sampleRate, C.MICROS_PER_SECOND),
        new WavPackTimestampSeeker(),
        /* durationUs= */ Util.scaleLargeTimestamp(totalSamples, C.MICROS_PER_SECOND, sampleRate),
        /* floorTimePosition= */ firstBlockIndex,
        /* ceilingTimePosition= */ firstBlockIndex + totalSamples,
        /* floorBytePosition= */ firstBlockPosition,
        /* ceilingBytePosition= */ inputLength,
        /* approxBytesPerFrame= */ Util.scaleLargeValue(
            inputLength - firstBlockPosition,
            blockSamples,
            Math.max(1, totalSamples),
            RoundingMode.DOWN),
        MIN_SEARCH_RANGE);
  }

  private static final class WavPackTimestampSeeker implements TimestampSeeker {

    /** Large enough to hold the biggest block, so a header is always found when there's one. */
    private static final int MAX_SEARCH_BYTES = (1 << 20) + 2 * WavPackBlockHeader.SIZE;

    private static final int SCAN_CHUNK_SIZE = 32 * 1024;

    private final ParsableByteArray scanBuffer;

    public WavPackTimestampSeeker() {
      scanBuffer = new ParsableByteArray(SCAN_CHUNK_SIZE + WavPackBlockHeader.SIZE);
    }

    @Override
    public TimestampSearchResult searchForTimestamp(ExtractorInput input, long targetBlockIndex)
        throws IOException {
      long searchPosition = input.getPosition();
      @Nullable WavPackBlockHeader header = findNextInitialBlock(input);
      if (header == null) {
        return TimestampSearchResult.NO_TIMESTAMP_IN_RANGE_RESULT;
      }
      long blockPosition = input.getPeekPosition();
      if (targetBlockIndex < header.blockIndex) {
        return TimestampSearchResult.overestimatedResult(header.blockIndex, searchPosition);
      }
      if (targetBlockIndex < header.blockIndex + header.blockSamples) {
        return TimestampSearchResult.targetFoundResult(blockPosition);
      }
      // The floor moves past this block, the next search starts at what follows it.
      return TimestampSearchResult.underestimatedResult(
          header.blockIndex + header.blockSamples, blockPosition + header.blockSize);
    }

    /**
     * Scans forward from the read position for the header of an initial block holding samples,
     * leaving the peek position at its start.
     */
    @Nullable
    private WavPackBlockHeader findNextInitialBlock(ExtractorInput input) throws IOException {
      byte[] data = scanBuffer.getData();
      int scanned = 0;
      int buffered = 0;
      while (scanned < MAX_SEARCH_BYTES) {
        int peeked = input.peek(data, buffered, SCAN_CHUNK_SIZE);
        if (peeked == C.RESULT_END_OF_INPUT) {
          return null;
        }
        buffered += peeked;
        int lastHeaderStart = buffered - WavPackBlockHeader.SIZE;
        for (int i = 0; i <= lastHeaderStart; i++) {
          if (data[i] != 'w') {
            continue;
          }
          scanBuffer.setPosition(i);
          @Nullable WavPackBlockHeader header = WavPackBlockHeader.parse(scanBuffer);
          if (header != null && header.isInitialBlock() && header.blockSamples > 0) {
            input.resetPeekPosition();
            input.advancePeekPosition(scanned + i);
            return header;
          }
        }
        // Keeps the tail, a header may straddle two chunks.
        int keep = Math.max(0, Math.min(buffered, WavPackBlockHeader.SIZE - 1));
        System.arraycopy(data, buffered - keep, data, 0, keep);
        scanned += buffered - keep;
        buffered = keep;
      }
      return null;
    }
  }
}
