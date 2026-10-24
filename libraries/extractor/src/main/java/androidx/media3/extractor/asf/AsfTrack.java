// by claude
package androidx.media3.extractor.asf;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

/**
 * Joins the payloads of a stream into media objects and outputs each complete one as a sample.
 * An object that misses a payload is dropped.
 */
/* package */ final class AsfTrack {

  private final TrackOutput trackOutput;
  private final boolean isAudio;
  private final long prerollUs;
  private final int descramblingSpan;
  private final int descramblingPacketSize;
  private final int descramblingChunkSize;
  private final ParsableByteArray objectData;

  private byte[] descrambledData;
  private boolean isObjectPending;
  private int objectSize;
  private int objectBytesRead;
  private long objectTimeUs;
  private boolean isKeyFrame;

  public AsfTrack(TrackOutput trackOutput, AsfHeader.Stream stream, long prerollMs) {
    this.trackOutput = trackOutput;
    isAudio = stream.trackType == C.TRACK_TYPE_AUDIO;
    prerollUs = prerollMs * 1000;
    descramblingSpan = stream.descramblingSpan;
    descramblingPacketSize = stream.descramblingPacketSize;
    descramblingChunkSize = stream.descramblingChunkSize;
    objectData = new ParsableByteArray();
    descrambledData = new byte[0];
  }

  public void reset() {
    isObjectPending = false;
  }

  public void onPayload(
      boolean isKeyFrame,
      int objectOffset,
      int objectSize,
      long presentationTimeMs,
      byte[] data,
      int offset,
      int length) {
    if (objectSize <= 0) {
      return;
    }
    if (objectOffset == 0) {
      startObject(objectSize, presentationTimeMs, isKeyFrame);
    } else if (!isObjectPending || objectSize != this.objectSize) {
      return;
    }
    if (objectOffset + length > this.objectSize) {
      return;
    }
    System.arraycopy(data, offset, objectData.getData(), objectOffset, length);
    objectBytesRead += length;
    if (objectBytesRead >= this.objectSize) {
      outputObject();
    }
  }

  private void startObject(int objectSize, long presentationTimeMs, boolean isKeyFrame) {
    isObjectPending = true;
    this.objectSize = objectSize;
    objectBytesRead = 0;
    objectTimeUs = presentationTimeMs * 1000 - prerollUs;
    this.isKeyFrame = isAudio || isKeyFrame;
    objectData.reset(objectSize);
  }

  private void outputObject() {
    isObjectPending = false;
    if (descramblingSpan > 1 && objectSize == descramblingPacketSize * descramblingSpan) {
      descramble();
    }
    trackOutput.sampleData(objectData, objectSize);
    @C.BufferFlags int flags = isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0;
    trackOutput.sampleMetadata(
        objectTimeUs, flags, objectSize, /* offset= */ 0, /* cryptoData= */ null);
  }

  /** Audio spread error correction stores the chunks of {@code descramblingSpan} rows by column. */
  private void descramble() {
    if (descrambledData.length < objectSize) {
      descrambledData = new byte[objectSize];
    }
    byte[] scrambledData = objectData.getData();
    int chunksPerPacket = descramblingPacketSize / descramblingChunkSize;
    for (int offset = 0; offset < objectSize; offset += descramblingChunkSize) {
      int chunkIndex = offset / descramblingChunkSize;
      int row = chunkIndex / descramblingSpan;
      int column = chunkIndex % descramblingSpan;
      int sourceIndex = row + column * chunksPerPacket;
      System.arraycopy(
          scrambledData,
          sourceIndex * descramblingChunkSize,
          descrambledData,
          offset,
          descramblingChunkSize);
    }
    byte[] objectBytes = objectData.getData();
    objectData.reset(descrambledData, objectSize);
    descrambledData = objectBytes;
  }
}
