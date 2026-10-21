// by claude
package androidx.media3.extractor;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.extractor.metadata.id3.Id3Decoder.ID3_HEADER_LENGTH;

import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.metadata.id3.Id3Decoder;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;

/**
 * Reads containers that have ID3v2 tags written in front of them as if the tags were not there, the
 * way taggers that only know MP3 leave any file named .mp3. It goes after every other extractor, so
 * it only runs on input none of them recognized. Offsets inside the container stay relative to its
 * own start.
 */
/* package */ final class LeadingId3SkippingExtractor implements Extractor {

  private static final int ID3_FLAG_FOOTER_PRESENT = 0x10;
  private static final long RELOAD_MINIMUM_SEEK_DISTANCE = 256 * 1024;

  private final Supplier<Extractor[]> candidatesSupplier;
  private final ParsableByteArray scratch;

  private long containerStart;
  @Nullable private Extractor delegate;
  @Nullable private ExtractorInput lastInput;
  @Nullable private StartOffsetExtractorInput lastContainerInput;

  /**
   * @param candidatesSupplier Creates the extractors tried behind the tags, called only when the
   *     input starts with ID3v2 tags. Their sniff must keep peeking from where it is called.
   */
  /* package */ LeadingId3SkippingExtractor(Supplier<Extractor[]> candidatesSupplier) {
    this.candidatesSupplier = candidatesSupplier;
    scratch = new ParsableByteArray(ID3_HEADER_LENGTH);
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    containerStart = peekLeadingId3Length(input);
    if (containerStart == 0) {
      return false;
    }
    for (Extractor candidate : candidatesSupplier.get()) {
      try {
        if (candidate.sniff(input)) {
          delegate = candidate;
          return true;
        }
      } catch (EOFException e) {
        // Too short for this candidate.
      } finally {
        input.resetPeekPosition();
        input.advancePeekPosition((int) containerStart);
      }
    }
    return false;
  }

  @Override
  public List<SniffFailure> getSniffFailureDetails() {
    return ImmutableList.of();
  }

  @Override
  public void init(ExtractorOutput output) {
    checkNotNull(delegate).init(new StartOffsetExtractorOutput(containerStart, output));
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    long position = input.getPosition();
    if (position < containerStart) {
      long bytesToSkip = containerStart - position;
      if (bytesToSkip >= RELOAD_MINIMUM_SEEK_DISTANCE) {
        seekPosition.position = containerStart;
        return RESULT_SEEK;
      }
      input.skipFully((int) bytesToSkip);
    }

    if (input != lastInput || lastContainerInput == null) {
      lastInput = input;
      lastContainerInput = new StartOffsetExtractorInput(input, containerStart);
    }
    @ReadResult int result = checkNotNull(delegate).read(lastContainerInput, seekPosition);
    if (result == RESULT_SEEK) {
      seekPosition.position += containerStart;
    }
    return result;
  }

  @Override
  public void seek(long position, long timeUs) {
    checkNotNull(delegate).seek(Math.max(0, position - containerStart), timeUs);
  }

  @Override
  public void release() {
    if (delegate != null) {
      delegate.release();
    }
  }

  @Override
  public Extractor getUnderlyingImplementation() {
    return delegate == null ? this : delegate.getUnderlyingImplementation();
  }

  /** Returns the length of the ID3v2 tags the input starts with and peeks past them. */
  private long peekLeadingId3Length(ExtractorInput input) throws IOException {
    int length = 0;
    while (input.peekFully(scratch.getData(), 0, ID3_HEADER_LENGTH, /* allowEndOfInput= */ true)) {
      scratch.setPosition(0);
      if (scratch.readUnsignedInt24() != Id3Decoder.ID3_TAG) {
        break;
      }
      scratch.skipBytes(2); // major and minor version
      int flags = scratch.readUnsignedByte();
      int tagLength = ID3_HEADER_LENGTH + scratch.readSynchSafeInt();
      if ((flags & ID3_FLAG_FOOTER_PRESENT) != 0) {
        tagLength += ID3_HEADER_LENGTH;
      }
      input.advancePeekPosition(tagLength - ID3_HEADER_LENGTH);
      length += tagLength;
    }
    input.resetPeekPosition();
    input.advancePeekPosition(length);
    return length;
  }
}
