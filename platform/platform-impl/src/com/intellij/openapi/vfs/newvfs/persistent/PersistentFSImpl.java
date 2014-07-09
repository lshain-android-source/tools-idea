/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vfs.newvfs.persistent;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.LowMemoryWatcher;
import com.intellij.openapi.util.ShutDownTracker;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.*;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.openapi.vfs.newvfs.impl.FakeVirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.ConcurrentIntObjectMap;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.StripedLockIntObjectConcurrentHashMap;
import com.intellij.util.io.ReplicatorInputStream;
import com.intellij.util.messages.MessageBus;
import gnu.trove.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author max
 */
public class PersistentFSImpl extends PersistentFS implements ApplicationComponent {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vfs.newvfs.persistent.PersistentFS");

  private final MessageBus myEventsBus;

  private final ReadWriteLock myRootsLock = new ReentrantReadWriteLock();
  // (normalized)url -> root. guarded by myRootsLock
  private final Map<String, VirtualFileSystemEntry> myRoots = new THashMap<String, VirtualFileSystemEntry>(FileUtil.PATH_HASHING_STRATEGY);
  // root.getId() -> root. guarded by myRootsLock
  private final TIntObjectHashMap<VirtualFileSystemEntry> myRootsById = new TIntObjectHashMap<VirtualFileSystemEntry>();

  private final ConcurrentIntObjectMap<VirtualFileSystemEntry> myIdToDirCache = new StripedLockIntObjectConcurrentHashMap<VirtualFileSystemEntry>();
  private final Object myInputLock = new Object();

  // the root of all roots. All roots in myRoots and myRootsById maps are children of this super root. guarded by myRootsLock
  @Nullable private volatile VirtualFileSystemEntry mySuperRoot;
  private boolean myShutDown = false;
  @SuppressWarnings("UnusedDeclaration")
  private final LowMemoryWatcher myLowMemoryWatcher = LowMemoryWatcher.register(new Runnable() {
    @Override
    public void run() {
      clearIdCache();
    }
  });

  public PersistentFSImpl(@NotNull final MessageBus bus) {
    myEventsBus = bus;
    ShutDownTracker.getInstance().registerShutdownTask(new Runnable() {
      @Override
      public void run() {
        performShutdown();
      }
    });
  }

  @Override
  public void disposeComponent() {
    performShutdown();
  }

  private synchronized void performShutdown() {
    if (!myShutDown) {
      myShutDown = true;
      LOG.info("VFS dispose started");
      FSRecords.dispose();
      LOG.info("VFS dispose completed");
    }
  }

  @Override
  @NonNls
  @NotNull
  public String getComponentName() {
    return "app.component.PersistentFS";
  }

  @Override
  public void initComponent() {
    FSRecords.connect();
  }

  @Override
  public boolean areChildrenLoaded(@NotNull final VirtualFile dir) {
    return areChildrenLoaded(getFileId(dir));
  }

  @Override
  public long getCreationTimestamp() {
    return FSRecords.getCreationTimestamp();
  }

  @NotNull
  private static NewVirtualFileSystem getDelegate(@NotNull VirtualFile file) {
    return (NewVirtualFileSystem)file.getFileSystem();
  }

  @Override
  public boolean wereChildrenAccessed(@NotNull final VirtualFile dir) {
    return FSRecords.wereChildrenAccessed(getFileId(dir));
  }

  @Override
  @NotNull
  public String[] list(@NotNull final VirtualFile file) {
    int id = getFileId(file);

    FSRecords.NameId[] nameIds = FSRecords.listAll(id);
    if (!areChildrenLoaded(id)) {
      nameIds  = persistAllChildren(file, id, nameIds);
    }
    return ContainerUtil.map2Array(nameIds, String.class, new Function<FSRecords.NameId, String>() {
      @Override
      public String fun(FSRecords.NameId id) {
        return id.name;
      }
    });
  }

  @Override
  @NotNull
  public String[] listPersisted(@NotNull VirtualFile parent) {
    return listPersisted(FSRecords.list(getFileId(parent)));
  }

  @NotNull
  private static String[] listPersisted(@NotNull int[] childrenIds) {
    String[] names = ArrayUtil.newStringArray(childrenIds.length);
    for (int i = 0; i < childrenIds.length; i++) {
      names[i] = FSRecords.getName(childrenIds[i]);
    }
    return names;
  }

  @NotNull
  private FSRecords.NameId[] persistAllChildren(@NotNull final VirtualFile file, final int id, @NotNull FSRecords.NameId[] current) {
    assert file != mySuperRoot;
    final NewVirtualFileSystem fs = replaceWithNativeFS(getDelegate(file));

    String[] delegateNames = VfsUtil.filterNames(fs.list(file));
    if (delegateNames.length == 0 && current.length > 0) {
      return current;
    }

    THashMap<String, FSRecords.NameId> result = new THashMap<String, FSRecords.NameId>();
    if (current.length == 0) {
      for (String name : delegateNames) {
        result.put(name, new FSRecords.NameId(-1, name));
      }
    }
    else {
      for (FSRecords.NameId nameId : current) {
        result.put(nameId.name, nameId);
      }
      for (String name : delegateNames) {
        if (!result.containsKey(name)) {
          result.put(name, new FSRecords.NameId(-1, name));
        }
      }
    }

    final TIntArrayList childrenIds = new TIntArrayList(result.size());
    final List<FSRecords.NameId> nameIds = ContainerUtil.newArrayListWithExpectedSize(result.size());
    result.forEachValue(new TObjectProcedure<FSRecords.NameId>() {
      @Override
      public boolean execute(FSRecords.NameId nameId) {
        if (nameId.id < 0) {
          FakeVirtualFile child = new FakeVirtualFile(file, nameId.name);
          FileAttributes attributes = fs.getAttributes(child);
          if (attributes != null) {
            int childId = createAndFillRecord(fs, child, id, attributes);
            nameId = new FSRecords.NameId(childId, nameId.name);
          }
        }
        if (nameId.id > 0) {
          childrenIds.add(nameId.id);
          nameIds.add(nameId);
        }
        return true;
      }
    });

    FSRecords.updateList(id, childrenIds.toNativeArray());
    setChildrenCached(id);

    return nameIds.toArray(new FSRecords.NameId[nameIds.size()]);
  }

  public static void setChildrenCached(int id) {
    int flags = FSRecords.getFlags(id);
    FSRecords.setFlags(id, flags | CHILDREN_CACHED_FLAG, true);
  }

  @Override
  @NotNull
  public FSRecords.NameId[] listAll(@NotNull VirtualFile parent) {
    final int parentId = getFileId(parent);

    FSRecords.NameId[] nameIds = FSRecords.listAll(parentId);
    if (!areChildrenLoaded(parentId)) {
      return persistAllChildren(parent, parentId, nameIds);
    }

    return nameIds;
  }

  private static boolean areChildrenLoaded(final int parentId) {
    return (FSRecords.getFlags(parentId) & CHILDREN_CACHED_FLAG) != 0;
  }

  @Override
  @Nullable
  public DataInputStream readAttribute(@NotNull final VirtualFile file, @NotNull final FileAttribute att) {
    return FSRecords.readAttributeWithLock(getFileId(file), att.getId());
  }

  @Override
  @NotNull
  public DataOutputStream writeAttribute(@NotNull final VirtualFile file, @NotNull final FileAttribute att) {
    return FSRecords.writeAttribute(getFileId(file), att.getId(), att.isFixedSize());
  }

  @Nullable
  private static DataInputStream readContent(@NotNull VirtualFile file) {
    return FSRecords.readContent(getFileId(file));
  }

  @Nullable
  private static DataInputStream readContentById(int contentId) {
    return FSRecords.readContentById(contentId);
  }

  @NotNull
  private static DataOutputStream writeContent(@NotNull VirtualFile file, boolean readOnly) {
    return FSRecords.writeContent(getFileId(file), readOnly);
  }

  private static void writeContent(@NotNull VirtualFile file, ByteSequence content, boolean readOnly) throws IOException {
    FSRecords.writeContent(getFileId(file), content, readOnly);
  }

  @Override
  public int storeUnlinkedContent(@NotNull byte[] bytes) {
    return FSRecords.storeUnlinkedContent(bytes);
  }

  @Override
  public int getModificationCount(@NotNull final VirtualFile file) {
    return FSRecords.getModCount(getFileId(file));
  }

  @Override
  public int getCheapFileSystemModificationCount() {
    return FSRecords.getLocalModCount();
  }

  @Override
  public int getFilesystemModificationCount() {
    return FSRecords.getModCount();
  }

  private static boolean writeAttributesToRecord(final int id,
                                                 final int parentId,
                                                 @NotNull VirtualFile file,
                                                 @NotNull NewVirtualFileSystem fs,
                                                 @NotNull FileAttributes attributes) {
    String name = file.getName();
    if (!name.isEmpty()) {
      if (namesEqual(fs, name, FSRecords.getName(id))) return false; // TODO: Handle root attributes change.
    }
    else {
      if (areChildrenLoaded(id)) return false; // TODO: hack
    }

    FSRecords.setParent(id, parentId);
    FSRecords.setName(id, name);

    FSRecords.setTimestamp(id, attributes.lastModified);
    FSRecords.setLength(id, attributes.isDirectory() ? -1L : attributes.length);

    FSRecords.setFlags(id, (attributes.isDirectory() ? IS_DIRECTORY_FLAG : 0) |
                           (attributes.isWritable() ? 0 : IS_READ_ONLY) |
                           (attributes.isSymLink() ? IS_SYMLINK : 0) |
                           (attributes.isSpecial() ? IS_SPECIAL : 0) |
                           (attributes.isHidden() ? IS_HIDDEN : 0), true);

    return true;
  }

  @Override
  public int getFileAttributes(int id) {
    assert id > 0;
    //noinspection MagicConstant
    return FSRecords.getFlags(id);
  }

  @Override
  public boolean isDirectory(@NotNull final VirtualFile file) {
    return isDirectory(getFileAttributes(getFileId(file)));
  }

  private static int getParent(final int id) {
    assert id > 0;
    return FSRecords.getParent(id);
  }

  private static boolean namesEqual(@NotNull VirtualFileSystem fs, @NotNull String n1, String n2) {
    return fs.isCaseSensitive() ? n1.equals(n2) : n1.equalsIgnoreCase(n2);
  }

  @Override
  public boolean exists(@NotNull final VirtualFile fileOrDirectory) {
    return ((VirtualFileWithId)fileOrDirectory).getId() > 0;
  }

  @Override
  public long getTimeStamp(@NotNull final VirtualFile file) {
    return FSRecords.getTimestamp(getFileId(file));
  }

  @Override
  public void setTimeStamp(@NotNull final VirtualFile file, final long modStamp) throws IOException {
    final int id = getFileId(file);
    FSRecords.setTimestamp(id, modStamp);
    getDelegate(file).setTimeStamp(file, modStamp);
  }

  private static int getFileId(@NotNull VirtualFile file) {
    final int id = ((VirtualFileWithId)file).getId();
    if (id <= 0) {
      throw new InvalidVirtualFileAccessException(file);
    }
    return id;
  }

  @Override
  public boolean isSymLink(@NotNull VirtualFile file) {
    return isSymLink(getFileAttributes(getFileId(file)));
  }

  @Override
  public String resolveSymLink(@NotNull VirtualFile file) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSpecialFile(@NotNull VirtualFile file) {
    return isSpecialFile(getFileAttributes(getFileId(file)));
  }

  @Override
  public boolean isWritable(@NotNull VirtualFile file) {
    return (getFileAttributes(getFileId(file)) & IS_READ_ONLY) == 0;
  }

  @Override
  public boolean isHidden(@NotNull VirtualFile file) {
    return (getFileAttributes(getFileId(file)) & IS_HIDDEN) != 0;
  }

  @Override
  public void setWritable(@NotNull final VirtualFile file, final boolean writableFlag) throws IOException {
    getDelegate(file).setWritable(file, writableFlag);
    processEvent(new VFilePropertyChangeEvent(this, file, VirtualFile.PROP_WRITABLE, isWritable(file), writableFlag, false));
  }

  @Override
  public int getId(@NotNull final VirtualFile parent, @NotNull final String childName, @NotNull final NewVirtualFileSystem fs) {
    if (parent == mySuperRoot) {
      String rootUrl = normalizeRootUrl(childName, fs);
      VirtualFileSystemEntry root = myRoots.get(rootUrl);
      return root == null ? 0 : root.getId();
    }

    int parentId = getFileId(parent);
    int[] children = FSRecords.list(parentId);

    if (children.length > 0) {
      // fast path, check that some child has same nameId as given name, this avoid O(N) on retrieving names for processing non-cached children
      int nameId = FSRecords.getNameId(childName);
      for (final int childId : children) {
        if (nameId == FSRecords.getNameId(childId)) {
          return childId;
        }
      }
      // for case sensitive system the above check is exhaustive in consistent state of vfs
    }

    for (final int childId : children) {
      if (namesEqual(fs, childName, FSRecords.getName(childId))) return childId;
    }

    final VirtualFile fake = new FakeVirtualFile(parent, childName);
    final FileAttributes attributes = fs.getAttributes(fake);
    if (attributes != null) {
      final int child = createAndFillRecord(fs, fake, parentId, attributes);
      FSRecords.updateList(parentId, ArrayUtil.append(children, child));
      return child;
    }

    return 0;
  }

  @Override
  public long getLength(@NotNull final VirtualFile file) {
    long len;
    if (mustReloadContent(file)) {
      len = reloadLengthFromDelegate(file, getDelegate(file));
    }
    else {
      final int id = getFileId(file);
      len = FSRecords.getLength(id);
    }

    return len;
  }

  @Override
  public VirtualFile copyFile(final Object requestor, @NotNull final VirtualFile file, @NotNull final VirtualFile newParent, @NotNull final String copyName)
    throws IOException {
    getDelegate(file).copyFile(requestor, file, newParent, copyName);
    processEvent(new VFileCopyEvent(requestor, file, newParent, copyName));

    final VirtualFile child = newParent.findChild(copyName);
    if (child == null) {
      throw new IOException("Cannot create child");
    }
    return child;
  }

  @Override
  public VirtualFile createChildDirectory(final Object requestor, @NotNull final VirtualFile parent, @NotNull final String dir) throws IOException {
    getDelegate(parent).createChildDirectory(requestor, parent, dir);
    processEvent(new VFileCreateEvent(requestor, parent, dir, true, false));

    final VirtualFile child = parent.findChild(dir);
    if (child == null) {
      throw new IOException("Cannot create child directory '" + dir + "' at " + parent.getPath());
    }
    return child;
  }

  @Override
  public VirtualFile createChildFile(final Object requestor, @NotNull final VirtualFile parent, @NotNull final String file) throws IOException {
    getDelegate(parent).createChildFile(requestor, parent, file);
    processEvent(new VFileCreateEvent(requestor, parent, file, false, false));

    final VirtualFile child = parent.findChild(file);
    if (child == null) {
      throw new IOException("Cannot create child file '" + file + "' at " + parent.getPath());
    }
    return child;
  }

  @Override
  public void deleteFile(final Object requestor, @NotNull final VirtualFile file) throws IOException {
    final NewVirtualFileSystem delegate = getDelegate(file);
    delegate.deleteFile(requestor, file);

    if (!delegate.exists(file)) {
      processEvent(new VFileDeleteEvent(requestor, file, false));
    }
  }

  @Override
  public void renameFile(final Object requestor, @NotNull final VirtualFile file, @NotNull final String newName) throws IOException {
    getDelegate(file).renameFile(requestor, file, newName);
    processEvent(new VFilePropertyChangeEvent(requestor, file, VirtualFile.PROP_NAME, file.getName(), newName, false));
  }

  @Override
  @NotNull
  public byte[] contentsToByteArray(@NotNull final VirtualFile file) throws IOException {
    return contentsToByteArray(file, true);
  }

  @Override
  @NotNull
  public byte[] contentsToByteArray(@NotNull final VirtualFile file, boolean cacheContent) throws IOException {
    InputStream contentStream = null;
    boolean reloadFromDelegate;
    boolean outdated;
    int fileId;
    synchronized (myInputLock) {
      fileId = getFileId(file);
      outdated = checkFlag(fileId, MUST_RELOAD_CONTENT) || FSRecords.getLength(fileId) == -1L;
      reloadFromDelegate = outdated || (contentStream = readContent(file)) == null;
    }

    if (reloadFromDelegate) {
      final NewVirtualFileSystem delegate = getDelegate(file);

      final byte[] content;
      if (outdated) {
        // in this case, file can have out-of-date length. so, update it first (it's needed for correct contentsToByteArray() work)
        // see IDEA-90813 for possible bugs
        FSRecords.setLength(fileId, delegate.getLength(file));
        content = delegate.contentsToByteArray(file);
      }
      else {
        // a bit of optimization
        content = delegate.contentsToByteArray(file);
        FSRecords.setLength(fileId, content.length);
      }

      ApplicationEx application = (ApplicationEx)ApplicationManager.getApplication();
      // we should cache every local files content
      // because the local history feature is currently depends on this cache,
      // perforce offline mode as well
      if ((!delegate.isReadOnly() ||
           // do not cache archive content unless asked
           cacheContent && !application.isInternal() && !application.isUnitTestMode()) &&
          content.length <= PersistentFSConstants.FILE_LENGTH_TO_CACHE_THRESHOLD) {
        synchronized (myInputLock) {
          writeContent(file, new ByteSequence(content), delegate.isReadOnly());
          setFlag(file, MUST_RELOAD_CONTENT, false);
        }
      }

      return content;
    }
    else {
      try {
        final int length = (int)file.getLength();
        assert length >= 0 : file;
        return FileUtil.loadBytes(contentStream, length);
      }
      catch (IOException e) {
        throw FSRecords.handleError(e);
      }
    }
  }

  @Override
  @NotNull
  public byte[] contentsToByteArray(int contentId) throws IOException {
    final DataInputStream stream = readContentById(contentId);
    assert stream != null : contentId;
    return FileUtil.loadBytes(stream);
  }

  @Override
  @NotNull
  public InputStream getInputStream(@NotNull final VirtualFile file) throws IOException {
    synchronized (myInputLock) {
      InputStream contentStream;
      if (mustReloadContent(file) || (contentStream = readContent(file)) == null) {
        NewVirtualFileSystem delegate = getDelegate(file);
        long len = reloadLengthFromDelegate(file, delegate);
        InputStream nativeStream = delegate.getInputStream(file);

        if (len > PersistentFSConstants.FILE_LENGTH_TO_CACHE_THRESHOLD) return nativeStream;
        return createReplicator(file, nativeStream, len, delegate.isReadOnly());
      }
      else {
        return contentStream;
      }
    }
  }

  private static long reloadLengthFromDelegate(@NotNull VirtualFile file, @NotNull NewVirtualFileSystem delegate) {
    final long len = delegate.getLength(file);
    FSRecords.setLength(getFileId(file), len);
    return len;
  }

  private InputStream createReplicator(@NotNull final VirtualFile file, final InputStream nativeStream, final long fileLength, final boolean readOnly)
    throws IOException {
    if (nativeStream instanceof BufferExposingByteArrayInputStream) {
      // optimization
      BufferExposingByteArrayInputStream  byteStream = (BufferExposingByteArrayInputStream )nativeStream;
      byte[] bytes = byteStream.getInternalBuffer();
      storeContentToStorage(fileLength, file, readOnly, bytes, bytes.length);
      return nativeStream;
    }
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
    final BufferExposingByteArrayOutputStream cache = new BufferExposingByteArrayOutputStream((int)fileLength);
    return new ReplicatorInputStream(nativeStream, cache) {
      @Override
      public void close() throws IOException {
        super.close();
        storeContentToStorage(fileLength, file, readOnly, cache.getInternalBuffer(), cache.size());
      }
    };
  }

  private void storeContentToStorage(long fileLength,
                                     @NotNull VirtualFile file,
                                     boolean readOnly, @NotNull byte[] bytes, int bytesLength)
    throws IOException {
    synchronized (myInputLock) {
      if (bytesLength == fileLength) {
        writeContent(file, new ByteSequence(bytes, 0, bytesLength), readOnly);
        setFlag(file, MUST_RELOAD_CONTENT, false);
      }
      else {
        setFlag(file, MUST_RELOAD_CONTENT, true);
      }
    }
  }

  private static boolean mustReloadContent(@NotNull VirtualFile file) {
    int fileId = getFileId(file);
    return checkFlag(fileId, MUST_RELOAD_CONTENT) || FSRecords.getLength(fileId) == -1L;
  }

  @Override
  @NotNull
  public OutputStream getOutputStream(@NotNull final VirtualFile file,
                                      final Object requestor,
                                      final long modStamp,
                                      final long timeStamp) throws IOException {
    final VFileContentChangeEvent event = new VFileContentChangeEvent(requestor, file, file.getModificationStamp(), modStamp, false);

    final List<VFileContentChangeEvent> events = Collections.singletonList(event);

    final BulkFileListener publisher = myEventsBus.syncPublisher(VirtualFileManager.VFS_CHANGES);
    publisher.before(events);

    return new ByteArrayOutputStream() {
      private boolean closed; // protection against user calling .close() twice
      @Override
      public void close() throws IOException {
        if (closed) return;
        super.close();

        NewVirtualFileSystem delegate = getDelegate(file);
        OutputStream ioFileStream = delegate.getOutputStream(file, requestor, modStamp, timeStamp);
        // FSRecords.ContentOutputStream already buffered, no need to wrap in BufferedStream
        OutputStream persistenceStream = writeContent(file, delegate.isReadOnly());

        try {
          persistenceStream.write(buf, 0, count);
        }
        finally {
          try {
            ioFileStream.write(buf, 0, count);
          }
          finally {
            closed = true;
            persistenceStream.close();
            ioFileStream.close();
            executeTouch(file, false, event.getModificationStamp());
            publisher.after(events);
          }
        }
      }
    };
  }

  @Override
  public int acquireContent(@NotNull VirtualFile file) {
    return FSRecords.acquireFileContent(getFileId(file));
  }

  @Override
  public void releaseContent(int contentId) {
    FSRecords.releaseContent(contentId);
  }

  @Override
  public int getCurrentContentId(@NotNull VirtualFile file) {
    return FSRecords.getContentId(getFileId(file));
  }

  @Override
  public void moveFile(final Object requestor, @NotNull final VirtualFile file, @NotNull final VirtualFile newParent) throws IOException {
    getDelegate(file).moveFile(requestor, file, newParent);
    processEvent(new VFileMoveEvent(requestor, file, newParent));
  }

  private void processEvent(@NotNull VFileEvent event) {
    processEvents(Collections.singletonList(event));
  }

  private static class EventWrapper {
    private final VFileDeleteEvent event;
    private final int id;

    private EventWrapper(final VFileDeleteEvent event, final int id) {
      this.event = event;
      this.id = id;
    }
  }

  @NotNull private static final Comparator<EventWrapper> DEPTH_COMPARATOR = new Comparator<EventWrapper>() {
    @Override
    public int compare(@NotNull final EventWrapper o1, @NotNull final EventWrapper o2) {
      return o1.event.getFileDepth() - o2.event.getFileDepth();
    }
  };

  @NotNull
  private static List<VFileEvent> validateEvents(@NotNull List<VFileEvent> events) {
    final List<EventWrapper> deletionEvents = ContainerUtil.newArrayList();
    for (int i = 0, size = events.size(); i < size; i++) {
      final VFileEvent event = events.get(i);
      if (event instanceof VFileDeleteEvent && event.isValid()) {
        deletionEvents.add(new EventWrapper((VFileDeleteEvent)event, i));
      }
    }

    ContainerUtil.quickSort(deletionEvents, DEPTH_COMPARATOR);

    final TIntHashSet invalidIDs = new TIntHashSet(deletionEvents.size());
    final List<VirtualFile> dirsToBeDeleted = new ArrayList<VirtualFile>();
    nextEvent:
    for (EventWrapper wrapper : deletionEvents) {
      final VirtualFile candidate = wrapper.event.getFile();
      for (VirtualFile file : dirsToBeDeleted) {
        if (VfsUtilCore.isAncestor(file, candidate, false)) {
          invalidIDs.add(wrapper.id);
          continue nextEvent;
        }
      }

      if (candidate.isDirectory()) {
        dirsToBeDeleted.add(candidate);
      }
    }

    final List<VFileEvent> filtered = ContainerUtil.newArrayListWithCapacity(events.size() - invalidIDs.size());
    for (int i = 0, size = events.size(); i < size; i++) {
      final VFileEvent event = events.get(i);
      if (event.isValid() && !(event instanceof VFileDeleteEvent && invalidIDs.contains(i))) {
        filtered.add(event);
      }
    }
    return filtered;
  }

  @Override
  public void processEvents(@NotNull List<VFileEvent> events) {
    ApplicationManager.getApplication().assertWriteAccessAllowed();

    List<VFileEvent> validated = validateEvents(events);

    BulkFileListener publisher = myEventsBus.syncPublisher(VirtualFileManager.VFS_CHANGES);
    publisher.before(validated);
    for (VFileEvent event : validated) {
      applyEvent(event);
    }
    publisher.after(validated);
  }

  @Override
  @Nullable
  public VirtualFileSystemEntry findRoot(@NotNull String basePath, @NotNull NewVirtualFileSystem fs) {
    String rootUrl = normalizeRootUrl(basePath, fs);

    boolean isFakeRoot = basePath.isEmpty();
    myRootsLock.readLock().lock();
    VirtualFileSystemEntry root;
    try {
      root = isFakeRoot ? mySuperRoot : myRoots.get(rootUrl);
      if (root != null) return root;
    }
    finally {
      myRootsLock.readLock().unlock();
    }

    myRootsLock.writeLock().lock();
    try {
      root = isFakeRoot ? mySuperRoot : myRoots.get(rootUrl);
      if (root != null) return root;

      int rootId = FSRecords.findRootRecord(rootUrl);
      root = myRootsById.get(rootId);
      if (root != null) return root;

      if (isFakeRoot) {
        // fake super-root
        root = new VirtualDirectoryImpl("", null, fs, rootId, 0) {
          @SuppressWarnings("NonSynchronizedMethodOverridesSynchronizedMethod")
          @Override
          @NotNull
          public VirtualFile[] getChildren() {
            return getRoots(getFileSystem());
          }

          @Override
          public VirtualFileSystemEntry findChild(@NotNull String name) {
            if (name.isEmpty()) return null;
            return findRoot(name, getFileSystem());
          }

          @Override
          protected char[] appendPathOnFileSystem(int pathLength, int[] position) {
            // getPath() for super-root should never be called.
            // however, when new FakeVirtualFile(superRoot, "name") is constructed,
            // return garbage to make sure they won't find anything by the name returned
            String fakeName = "@&^%$#*/\\(";
            int rootPathLength = pathLength + fakeName.length();
            char[] chars = new char[rootPathLength];

            position[0] = copyString(chars, position[0], fakeName);

            return chars;
          }
        };
      }
      else if (fs instanceof JarFileSystem) {
        // optimization: for jar roots do not store base path in the myName field, use local FS file's getPath()
        String parentPath = basePath.substring(0, basePath.indexOf(JarFileSystem.JAR_SEPARATOR));
        VirtualFile parentLocalFile = LocalFileSystem.getInstance().findFileByPath(parentPath);
        if (parentLocalFile == null) return null;

        // check one more time since the findFileByPath could have created the root (by reentering the findRoot)
        root = myRoots.get(rootUrl);
        if (root != null) return root;
        root = myRootsById.get(rootId);
        if (root != null) return root;

        root = new JarRoot(fs, rootId, parentLocalFile);
      }
      else {
        root = new VirtualDirectoryImpl(basePath, null, fs, rootId, 0){
          @Override
          protected char[] appendPathOnFileSystem(int pathLength, int[] position) {
            // do not call super method since we know it's the root
            String name = getName();
            int nameLength = name.length();
            boolean appendSlash = SystemInfo.isWindows && nameLength == 2 && name.charAt(1) == ':'
                                  && pathLength == 0 // otherwise we called this as a part of longer file path calculation and slash will be added anyway
              ;

            int rootPathLength = pathLength + nameLength;
            if (appendSlash) ++rootPathLength;
            char[] chars = new char[rootPathLength];

            position[0] = copyString(chars, position[0], name);

            if (appendSlash) {
              chars[position[0]++] = '/';
            }

            return chars;
          }
        };
      }

      if (isFakeRoot) {
        mySuperRoot = root;
      }
      else {
        FileAttributes attributes = fs.getAttributes(root);
        if (attributes == null || !attributes.isDirectory()) {
          return null;
        }
        final boolean newRoot = writeAttributesToRecord(rootId, 0, root, fs, attributes);
        if (!newRoot && attributes.lastModified != FSRecords.getTimestamp(rootId)) {
          root.markDirtyRecursively();
        }

        myRoots.put(rootUrl, root);
        myRootsById.put(rootId, root);

        if (rootId != root.getId()) throw new AssertionError();
      }

      return root;
    }
    finally {
      myRootsLock.writeLock().unlock();
    }
  }

  @NotNull
  private static String normalizeRootUrl(@NotNull String basePath, @NotNull NewVirtualFileSystem fs) {
    // need to protect against relative path of the form "/x/../y"
    String url = fs.getProtocol() + "://" + VfsImplUtil.normalize(fs, FileUtil.toCanonicalPath(basePath));
    return StringUtil.trimEnd(url, "/");
  }

  @Override
  public void clearIdCache() {
    myIdToDirCache.clear();
  }

  private static final int DEPTH_LIMIT = 75;

  @Override
  @Nullable
  public NewVirtualFile findFileById(final int id) {
    return findFileById(id, false, null, 0);
  }

  @Override
  public NewVirtualFile findFileByIdIfCached(final int id) {
    return findFileById(id, true, null, 0);
  }

  @Nullable
  private VirtualFileSystemEntry findFileById(int id, boolean cachedOnly, TIntArrayList visited, int mask) {
    VirtualFileSystemEntry cached = myIdToDirCache.get(id);
    if (cached != null) return cached;

    if (visited != null && (visited.size() >= DEPTH_LIMIT || (mask & id) == id && visited.contains(id))) {
      @NonNls String sb = "Dead loop detected in persistent FS (id=" + id + " cached-only=" + cachedOnly + "):";
      for (int i = 0; i < visited.size(); i++) {
        int _id = visited.get(i);
        sb += "\n  " + _id + " '" + getName(_id) + "' " +
          String.format("%02x", getFileAttributes(_id)) + ' ' + myIdToDirCache.containsKey(_id);
      }
      LOG.error(sb);
      return null;
    }
    if (visited == null) visited = new TIntArrayList(DEPTH_LIMIT);
    visited.add(id);

    int parentId = getParent(id);
    VirtualFileSystemEntry result;
    if (parentId == 0) {
      myRootsLock.readLock().lock();
      try {
        result = myRootsById.get(id);
      }
      finally {
        myRootsLock.readLock().unlock();
      }
    }
    else {
      VirtualFileSystemEntry parentFile = findFileById(parentId, cachedOnly, visited, mask |= id);
      if (parentFile instanceof VirtualDirectoryImpl) {
        result = ((VirtualDirectoryImpl)parentFile).findChildById(id, cachedOnly);
      }
      else {
        result = null;
      }
    }

    if (result != null && result.isDirectory()) {
      VirtualFileSystemEntry old = myIdToDirCache.put(id, result);
      if (old != null) result = old;
    }
    return result;
  }

  @Override
  @NotNull
  public VirtualFile[] getRoots() {
    myRootsLock.readLock().lock();
    try {
      Collection<VirtualFileSystemEntry> roots = myRoots.values();
      return VfsUtilCore.toVirtualFileArray(roots);
    }
    finally {
      myRootsLock.readLock().unlock();
    }
  }

  @Override
  @NotNull
  public VirtualFile[] getRoots(@NotNull final NewVirtualFileSystem fs) {
    final List<VirtualFile> roots = new ArrayList<VirtualFile>();

    myRootsLock.readLock().lock();
    try {
      for (NewVirtualFile root : myRoots.values()) {
        if (root.getFileSystem() == fs) {
          roots.add(root);
        }
      }
    }
    finally {
      myRootsLock.readLock().unlock();
    }

    return VfsUtilCore.toVirtualFileArray(roots);
  }

  @Override
  @NotNull
  public VirtualFile[] getLocalRoots() {
    final List<VirtualFile> roots = new ArrayList<VirtualFile>();

    myRootsLock.readLock().lock();
    try {
      for (NewVirtualFile root : myRoots.values()) {
        if (root.isInLocalFileSystem()) {
          roots.add(root);
        }
      }
    }
    finally {
      myRootsLock.readLock().unlock();
    }

    return VfsUtilCore.toVirtualFileArray(roots);
  }

  private VirtualFileSystemEntry applyEvent(@NotNull VFileEvent event) {
    try {
      if (event instanceof VFileCreateEvent) {
        final VFileCreateEvent createEvent = (VFileCreateEvent)event;
        return executeCreateChild(createEvent.getParent(), createEvent.getChildName());
      }
      else if (event instanceof VFileDeleteEvent) {
        final VFileDeleteEvent deleteEvent = (VFileDeleteEvent)event;
        executeDelete(deleteEvent.getFile());
      }
      else if (event instanceof VFileContentChangeEvent) {
        final VFileContentChangeEvent contentUpdateEvent = (VFileContentChangeEvent)event;
        executeTouch(contentUpdateEvent.getFile(), contentUpdateEvent.isFromRefresh(), contentUpdateEvent.getModificationStamp());
      }
      else if (event instanceof VFileCopyEvent) {
        final VFileCopyEvent copyEvent = (VFileCopyEvent)event;
        return executeCreateChild(copyEvent.getNewParent(), copyEvent.getNewChildName());
      }
      else if (event instanceof VFileMoveEvent) {
        final VFileMoveEvent moveEvent = (VFileMoveEvent)event;
        executeMove(moveEvent.getFile(), moveEvent.getNewParent());
      }
      else if (event instanceof VFilePropertyChangeEvent) {
        final VFilePropertyChangeEvent propertyChangeEvent = (VFilePropertyChangeEvent)event;
        if (VirtualFile.PROP_NAME.equals(propertyChangeEvent.getPropertyName())) {
          executeRename(propertyChangeEvent.getFile(), (String)propertyChangeEvent.getNewValue());
        }
        else if (VirtualFile.PROP_WRITABLE.equals(propertyChangeEvent.getPropertyName())) {
          executeSetWritable(propertyChangeEvent.getFile(), ((Boolean)propertyChangeEvent.getNewValue()).booleanValue());
        }
        else if (VirtualFile.PROP_HIDDEN.equals(propertyChangeEvent.getPropertyName())) {
          executeSetHidden(propertyChangeEvent.getFile(), ((Boolean)propertyChangeEvent.getNewValue()).booleanValue());
        }
      }
    }
    catch (Exception e) {
      // Exception applying single event should not prevent other events from applying.
      LOG.error(e);
    }
    return null;
  }

  @NotNull
  @NonNls
  public String toString() {
    return "PersistentFS";
  }

  private static VirtualFileSystemEntry executeCreateChild(@NotNull VirtualFile parent, @NotNull String name) {
    final NewVirtualFileSystem delegate = getDelegate(parent);
    final VirtualFile fake = new FakeVirtualFile(parent, name);
    final FileAttributes attributes = delegate.getAttributes(fake);
    if (attributes != null) {
      final int parentId = getFileId(parent);
      final int childId = createAndFillRecord(delegate, fake, parentId, attributes);
      appendIdToParentList(parentId, childId);
      assert parent instanceof VirtualDirectoryImpl : parent;
      final VirtualDirectoryImpl dir = (VirtualDirectoryImpl)parent;
      VirtualFileSystemEntry child = dir.createChild(name, childId, dir.getFileSystem());
      dir.addChild(child);
      return child;
    }
    return null;
  }

  private static int createAndFillRecord(@NotNull NewVirtualFileSystem delegateSystem,
                                         @NotNull VirtualFile delegateFile,
                                         int parentId,
                                         @NotNull FileAttributes attributes) {
    final int childId = FSRecords.createRecord();
    writeAttributesToRecord(childId, parentId, delegateFile, delegateSystem, attributes);
    return childId;
  }

  private static void appendIdToParentList(final int parentId, final int childId) {
    int[] childrenList = FSRecords.list(parentId);
    childrenList = ArrayUtil.append(childrenList, childId);
    FSRecords.updateList(parentId, childrenList);
  }

  private void executeDelete(@NotNull VirtualFile file) {
    if (!file.exists()) {
      LOG.error("Deleting a file, which does not exist: " + file.getPath());
      return;
    }
    clearIdCache();

    int id = getFileId(file);
    FSRecords.deleteRecordRecursively(id);

    final VirtualFile parent = file.getParent();
    final int parentId = parent == null ? 0 : getFileId(parent);

    if (parentId == 0) {
      String rootUrl = normalizeRootUrl(file.getPath(), (NewVirtualFileSystem)file.getFileSystem());
      myRootsLock.writeLock().lock();
      try {
        myRoots.remove(rootUrl);
        myRootsById.remove(id);
        FSRecords.deleteRootRecord(id);
      }
      finally {
        myRootsLock.writeLock().unlock();
      }
    }
    else {
      removeIdFromParentList(parentId, id, parent, file);
      VirtualDirectoryImpl directory = (VirtualDirectoryImpl)file.getParent();
      assert directory != null : file;
      directory.removeChild(file);
    }

    invalidateSubtree(file);
  }

  private static void invalidateSubtree(@NotNull VirtualFile file) {
    final VirtualFileSystemEntry impl = (VirtualFileSystemEntry)file;
    impl.invalidate();
    for (VirtualFile child : impl.getCachedChildren()) {
      invalidateSubtree(child);
    }
  }

  private static void removeIdFromParentList(final int parentId, final int id, @NotNull VirtualFile parent, VirtualFile file) {
    int[] childList = FSRecords.list(parentId);

    int index = ArrayUtil.indexOf(childList, id);
    if (index == -1) {
      throw new RuntimeException("Cannot find child (" + id + ")" + file
                                 + "\n\tin (" + parentId + ")" + parent
                                 + "\n\tactual children:" + Arrays.toString(childList));
    }
    childList = ArrayUtil.remove(childList, index);
    FSRecords.updateList(parentId, childList);
  }

  private static void executeRename(@NotNull VirtualFile file, @NotNull final String newName) {
    ((VirtualFileSystemEntry)file).setNewName(newName);
    final int id = getFileId(file);
    FSRecords.setName(id, newName);
  }

  private static void executeSetWritable(@NotNull VirtualFile file, boolean writableFlag) {
    setFlag(file, IS_READ_ONLY, !writableFlag);
    ((VirtualFileSystemEntry)file).updateProperty(VirtualFile.PROP_WRITABLE, writableFlag);
  }

  private static void executeSetHidden(@NotNull VirtualFile file, boolean hiddenFlag) {
    setFlag(file, IS_HIDDEN, hiddenFlag);
    ((VirtualFileSystemEntry)file).updateProperty(VirtualFile.PROP_HIDDEN, hiddenFlag);
  }

  private static void setFlag(@NotNull VirtualFile file, int mask, boolean value) {
    setFlag(getFileId(file), mask, value);
  }

  private static void setFlag(final int id, final int mask, final boolean value) {
    int oldFlags = FSRecords.getFlags(id);
    int flags = value ? oldFlags | mask : oldFlags & ~mask;

    if (oldFlags != flags) {
      FSRecords.setFlags(id, flags, true);
    }
  }

  private static boolean checkFlag(int fileId, int mask) {
    return (FSRecords.getFlags(fileId) & mask) != 0;
  }

  private static void executeTouch(@NotNull VirtualFile file, boolean reloadContentFromDelegate, long newModificationStamp) {
    if (reloadContentFromDelegate) {
      setFlag(file, MUST_RELOAD_CONTENT, true);
    }

    final NewVirtualFileSystem delegate = getDelegate(file);
    final FileAttributes attributes = delegate.getAttributes(file);
    FSRecords.setLength(getFileId(file), attributes != null ? attributes.length : DEFAULT_LENGTH);
    FSRecords.setTimestamp(getFileId(file), attributes != null ? attributes.lastModified : DEFAULT_TIMESTAMP);

    ((VirtualFileSystemEntry)file).setModificationStamp(newModificationStamp);
  }

  private void executeMove(@NotNull VirtualFile file, @NotNull VirtualFile newParent) {
    clearIdCache();

    final int fileId = getFileId(file);
    final int newParentId = getFileId(newParent);
    final int oldParentId = getFileId(file.getParent());

    removeIdFromParentList(oldParentId, fileId, file.getParent(), file);
    appendIdToParentList(newParentId, fileId);

    ((VirtualFileSystemEntry)file).setParent(newParent);
    FSRecords.setParent(fileId, newParentId);
  }

  @Override
  public String getName(int id) {
    assert id > 0;
    return FSRecords.getName(id);
  }

  @TestOnly
  public void cleanPersistedContents() {
    final int[] roots = FSRecords.listRoots();
    for (int root : roots) {
      cleanPersistedContentsRecursively(root);
    }
  }

  @TestOnly
  private void cleanPersistedContentsRecursively(int id) {
    if (isDirectory(getFileAttributes(id))) {
      for (int child : FSRecords.list(id)) {
        cleanPersistedContentsRecursively(child);
      }
    }
    else {
      setFlag(id, MUST_RELOAD_CONTENT, true);
    }
  }

  public static class JarRoot extends VirtualDirectoryImpl {
    private final VirtualFile myParentLocalFile;

    private JarRoot(@NotNull NewVirtualFileSystem fs, int rootId, @NotNull VirtualFile parentLocalFile) {
      super("", null, fs, rootId, 0);
      myParentLocalFile = parentLocalFile;
    }

    @NotNull
    @Override
    public String getName() {
      return myParentLocalFile.getName();
    }

    @Override
    protected char[] appendPathOnFileSystem(int accumulatedPathLength, int[] positionRef) {
      String parentPath = myParentLocalFile.getPath();
      char[] chars = new char[parentPath.length() + JarFileSystem.JAR_SEPARATOR.length() + accumulatedPathLength];
      positionRef[0] = copyString(chars, positionRef[0], myParentLocalFile.getPath());
      positionRef[0] = copyString(chars, positionRef[0], JarFileSystem.JAR_SEPARATOR);
      return chars;
    }

    @Override
    public void setParent(@NotNull VirtualFile newParent) {
      throw new IncorrectOperationException();
    }

    @NotNull
    public VirtualFile getParentLocalFile() {
      return myParentLocalFile;
    }
  }
}
