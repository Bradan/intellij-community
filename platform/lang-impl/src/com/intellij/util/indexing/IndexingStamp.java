// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing;

import com.intellij.concurrency.ConcurrentCollectionFactory;
import com.intellij.openapi.vfs.InvalidVirtualFileAccessException;
import com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import com.intellij.util.ArrayUtil;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ConcurrentIntObjectMap;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A file has three indexed states (per particular index): indexed (with particular index_stamp which monotonically increases), outdated and (trivial) unindexed.
 * <ul>
 *   <li>If index version is advanced or we rebuild it then index_stamp is advanced, we rebuild everything.</li>
 *   <li>If we get remove file event -> we should remove all indexed state from indices data for it (if state is nontrivial)
 *  * and set its indexed state to outdated.</li>
 *   <li>If we get other event we set indexed state to outdated.</li>
 * </ul>
 */
public final class IndexingStamp {
  static final long INDEX_DATA_OUTDATED_STAMP = -2L;
  static final long HAS_NO_INDEXED_DATA_STAMP = 0L;

  private IndexingStamp() { }

  @NotNull
  public static FileIndexingState isFileIndexedStateCurrent(int fileId, @NotNull ID<?, ?> indexName) {
    try {
      long stamp = getIndexStamp(fileId, indexName);
      if (stamp == HAS_NO_INDEXED_DATA_STAMP) return FileIndexingState.NOT_INDEXED;
      return stamp == IndexVersion.getIndexCreationStamp(indexName) ? FileIndexingState.UP_TO_DATE : FileIndexingState.OUT_DATED;
    }
    catch (RuntimeException e) {
      final Throwable cause = e.getCause();
      if (!(cause instanceof IOException)) {
        throw e; // in case of IO exceptions consider file unindexed
      }
    }

    return FileIndexingState.OUT_DATED;
  }

  public static void setFileIndexedStateCurrent(int fileId, @NotNull ID<?, ?> id) {
    update(fileId, id, IndexVersion.getIndexCreationStamp(id));
  }

  public static void setFileIndexedStateOutdated(int fileId, @NotNull ID<?, ?> id) {
    update(fileId, id, INDEX_DATA_OUTDATED_STAMP);
  }

  public static void setFileIndexedStateUnindexed(int fileId, @NotNull ID<?, ?> id) {
    update(fileId, id, HAS_NO_INDEXED_DATA_STAMP);
  }

  private static final int INDEXING_STAMP_CACHE_CAPACITY = SystemProperties.getIntProperty("index.timestamp.cache.size", 100);
  private static final ConcurrentIntObjectMap<Timestamps> ourTimestampsCache =
    ConcurrentCollectionFactory.createConcurrentIntObjectMap();
  private static final BlockingQueue<Integer> ourFinishedFiles = new ArrayBlockingQueue<>(INDEXING_STAMP_CACHE_CAPACITY);

  @TestOnly
  public static void dropTimestampMemoryCaches() {
    flushCaches();
    ourTimestampsCache.clear();
  }

  public static long getIndexStamp(int fileId, ID<?, ?> indexName) {
    return ourLock.withReadLock(fileId, () -> {
      Timestamps stamp = createOrGetTimeStamp(fileId);
      return stamp.get(indexName);
    });
  }

  @TestOnly
  public static void dropIndexingTimeStamps(int fileId) throws IOException {
    ourTimestampsCache.remove(fileId);
    try (DataOutputStream out = FSRecords.writeAttribute(fileId, Timestamps.PERSISTENCE)) {
      Timestamps.readTimestamps((DataInputStream)null).writeToStream(out);
    }
  }

  @NotNull
  private static Timestamps createOrGetTimeStamp(int id) {
    return getTimestamp(id, true);
  }

  @Contract("_, true->!null")
  private static Timestamps getTimestamp(int id, boolean createIfNoneSaved) {
    assert id > 0;
    Timestamps timestamps = ourTimestampsCache.get(id);
    if (timestamps == null) {
      if (FSRecords.supportsRawAttributesAccess()) {
        try {
          timestamps = FSRecords.readAttributeRawWithLock(id, Timestamps.PERSISTENCE, Timestamps::readTimestamps);
          if (timestamps == null) {
            if (createIfNoneSaved) {
              timestamps = Timestamps.readTimestamps((DataInputStream)null);
            }
            else {
              return null;
            }
          }
        }
        catch (IOException e) {
          throw FSRecords.handleError(e);
        }
      }
      else {
        try (final DataInputStream stream = FSRecords.readAttributeWithLock(id, Timestamps.PERSISTENCE)) {
          if (stream == null && !createIfNoneSaved) return null;
          timestamps = Timestamps.readTimestamps(stream);
        }
        catch (IOException e) {
          throw FSRecords.handleError(e);
        }
      }
      ourTimestampsCache.cacheOrGet(id, timestamps);
    }
    return timestamps;
  }

  @TestOnly
  public static boolean hasIndexingTimeStamp(int fileId) {
    Timestamps timestamp = getTimestamp(fileId, false);
    return timestamp != null && timestamp.hasIndexingTimeStamp();
  }

  public static void update(int fileId, @NotNull ID<?, ?> indexName, final long indexCreationStamp) {
    assert fileId > 0;
    ourLock.withWriteLock(fileId, () -> {
      Timestamps stamp = createOrGetTimeStamp(fileId);
      stamp.set(indexName, indexCreationStamp);
      return null;
    });
  }

  public static @NotNull List<ID<?, ?>> getNontrivialFileIndexedStates(int fileId) {
    return ourLock.withReadLock(fileId, () -> {
      try {
        Timestamps stamp = createOrGetTimeStamp(fileId);
        if (stamp.hasIndexingTimeStamp()) {
          return List.copyOf(stamp.getIndexIds());
        }
      }
      catch (InvalidVirtualFileAccessException ignored /*ok to ignore it here*/) {
      }
      return Collections.emptyList();
    });
  }

  public static void flushCaches() {
    doFlush();
  }

  public static void flushCache(int finishedFile) {
    boolean exit = ourLock.withReadLock(finishedFile, () -> {
      Timestamps timestamps = ourTimestampsCache.get(finishedFile);
      if (timestamps == null) return true;
      if (!timestamps.isDirty()) {
        ourTimestampsCache.remove(finishedFile);
        return true;
      }
      return false;
    });
    if (exit) return;

    while (!ourFinishedFiles.offer(finishedFile)) {
      doFlush();
    }
  }

  @TestOnly
  public static int @NotNull [] dumpCachedUnfinishedFiles() {
    return ourLock.withAllLocksWriteLocked(() -> {
      int[] cachedKeys = ourTimestampsCache
        .entrySet()
        .stream()
        .filter(e -> e.getValue().isDirty())
        .mapToInt(e -> e.getKey())
        .toArray();

      if (cachedKeys.length == 0) {
        return ArrayUtil.EMPTY_INT_ARRAY;
      }
      else {
        IntSet cachedIds = new IntArraySet(cachedKeys);
        Set<Integer> finishedIds = new HashSet<>(ourFinishedFiles);
        cachedIds.removeAll(finishedIds);
        return cachedIds.toIntArray();
      }
    });
  }

  private static void doFlush() {
    List<Integer> files = new ArrayList<>(ourFinishedFiles.size());
    ourFinishedFiles.drainTo(files);

    if (!files.isEmpty()) {
      for (Integer fileId : files) {
        RuntimeException exception = ourLock.withWriteLock(fileId, () -> {
          try {
            final Timestamps timestamp = ourTimestampsCache.remove(fileId);
            if (timestamp == null) return null;

            if (timestamp.isDirty() /*&& file.isValid()*/) {
              //RC: now I don't see the benefits of implementing timestamps write via raw attribute bytebuffer access
              //    doFlush() is mostly outside the critical path, while implementing timestamps.writeToBuffer(buffer)
              //    is complicated with all those variable-sized numbers used.
              try (DataOutputStream sink = FSRecords.writeAttribute(fileId, Timestamps.PERSISTENCE)) {
                timestamp.writeToStream(sink);
              }
            }
            return null;
          }
          catch (IOException e) {
            return new RuntimeException(e);
          }
        });
        if (exception != null) {
          throw exception;
        }
      }
    }
  }

  static boolean isDirty() {
    return !ourFinishedFiles.isEmpty();
  }

  private static final StripedLock ourLock = new StripedLock();
}