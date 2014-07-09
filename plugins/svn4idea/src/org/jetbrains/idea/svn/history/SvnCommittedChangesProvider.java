/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

package org.jetbrains.idea.svn.history;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.committed.*;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings;
import com.intellij.openapi.vcs.versionBrowser.ChangesBrowserSettingsEditor;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.AsynchConsumer;
import com.intellij.util.Consumer;
import com.intellij.util.PairConsumer;
import com.intellij.util.ThrowableConsumer;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.messages.MessageBusConnection;
import icons.SvnIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.*;
import org.jetbrains.idea.svn.actions.ConfigureBranchesAction;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.tmatesoft.svn.core.wc.SVNLogClient;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNWCClient;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author yole
 */
public class SvnCommittedChangesProvider implements CachingCommittedChangesProvider<SvnChangeList, ChangeBrowserSettings> {
  private final Project myProject;
  private final SvnVcs myVcs;
  private final MessageBusConnection myConnection;
  private MergeInfoUpdatesListener myMergeInfoUpdatesListener;
  private final MyZipper myZipper;

  public final static int VERSION_WITH_COPY_PATHS_ADDED = 2;
  public final static int VERSION_WITH_REPLACED_PATHS = 3;

  private static final Logger LOG = Logger.getInstance("#org.jetbrains.idea.svn.history.SvnCommittedChangesProvider");

  public SvnCommittedChangesProvider(final Project project) {
    myProject = project;
    myVcs = SvnVcs.getInstance(myProject);
    myZipper = new MyZipper();

    myConnection = myProject.getMessageBus().connect();

    myConnection.subscribe(VcsConfigurationChangeListener.BRANCHES_CHANGED_RESPONSE, new VcsConfigurationChangeListener.DetailedNotification() {
      public void execute(final Project project, final VirtualFile vcsRoot, final List<CommittedChangeList> cachedList) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            if (project.isDisposed()) {
              return;
            }
            for (CommittedChangeList committedChangeList : cachedList) {
              if ((committedChangeList instanceof SvnChangeList) &&
                  ((vcsRoot == null) || (vcsRoot.equals(((SvnChangeList)committedChangeList).getVcsRoot())))) {
                ((SvnChangeList) committedChangeList).forceReloadCachedInfo(true);
              }
            }
          }
        });
      }
    });
  }

  @NotNull
  public ChangeBrowserSettings createDefaultSettings() {
    return new ChangeBrowserSettings();
  }

  public ChangesBrowserSettingsEditor<ChangeBrowserSettings> createFilterUI(final boolean showDateFilter) {
    return new SvnVersionFilterComponent(showDateFilter);
  }

  @Nullable
  public RepositoryLocation getLocationFor(final FilePath root) {
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    final String url = SvnUtil.getExactLocation(myVcs, root.getIOFile());
    return url == null ? null : new SvnRepositoryLocation(url);
  }

  public RepositoryLocation getLocationFor(final FilePath root, final String repositoryPath) {
    if (repositoryPath == null) {
      return getLocationFor(root);
    }

    return new SvnLoadingRepositoryLocation(repositoryPath, myVcs);
  }

  @Nullable
  public VcsCommittedListsZipper getZipper() {
    return myZipper;
  }

  private class MyZipper implements VcsCommittedListsZipper {
    public Pair<List<RepositoryLocationGroup>, List<RepositoryLocation>> groupLocations(final List<RepositoryLocation> in) {
      final List<RepositoryLocationGroup> groups = new ArrayList<RepositoryLocationGroup>();
      final List<RepositoryLocation> singles = new ArrayList<RepositoryLocation>();

      final MultiMap<SVNURL, RepositoryLocation> map = new MultiMap<SVNURL, RepositoryLocation>();

      for (RepositoryLocation location : in) {
        final SvnRepositoryLocation svnLocation = (SvnRepositoryLocation) location;
        final String url = svnLocation.getURL();

        final SVNURL root = SvnUtil.getRepositoryRoot(myVcs, url);
        if (root == null) {
          // should not occur
          LOG.info("repository root not found for location:"+ location.toPresentableString());
          singles.add(location);
        } else {
          map.putValue(root, svnLocation);
        }
      }

      final Set<SVNURL> keys = map.keySet();
      for (SVNURL key : keys) {
        final Collection<RepositoryLocation> repositoryLocations = map.get(key);
        if (repositoryLocations.size() == 1) {
          singles.add(repositoryLocations.iterator().next());
        } else {
          final SvnRepositoryLocationGroup group = new SvnRepositoryLocationGroup(key, repositoryLocations);
          groups.add(group);
        }
      }
      return new Pair<List<RepositoryLocationGroup>, List<RepositoryLocation>>(groups, singles);
    }

    public CommittedChangeList zip(final RepositoryLocationGroup group, final List<CommittedChangeList> lists) {
      return new SvnChangeList(lists, new SvnRepositoryLocation(group.toPresentableString()));
    }

    public long getNumber(final CommittedChangeList list) {
      return list.getNumber();
    }
  }

  public void loadCommittedChanges(ChangeBrowserSettings settings,
                                   RepositoryLocation location,
                                   int maxCount,
                                   final AsynchConsumer<CommittedChangeList> consumer)
    throws VcsException {
    try {
      final SvnRepositoryLocation svnLocation = (SvnRepositoryLocation) location;
      final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
      if (progress != null) {
        progress.setText(SvnBundle.message("progress.text.changes.collecting.changes"));
        progress.setText2(SvnBundle.message("progress.text2.changes.establishing.connection", location));
      }

      final String repositoryRoot;
      SVNRepository repository = null;
      try {
        repository = myVcs.createRepository(svnLocation.getURL());
        repositoryRoot = repository.getRepositoryRoot(true).toString();
      }
      catch (SVNException e) {
        throw new VcsException(e);
      } finally {
        if (repository != null) {
          repository.closeSession();
        }
      }

      final ChangeBrowserSettings.Filter filter = settings.createFilter();

      getCommittedChangesImpl(settings, svnLocation.getURL(), new String[]{""}, maxCount, new Consumer<SVNLogEntry>() {
        public void consume(final SVNLogEntry svnLogEntry) {
          final SvnChangeList cl = new SvnChangeList(myVcs, svnLocation, svnLogEntry, repositoryRoot);
          if (filter.accepts(cl)) {
            consumer.consume(cl);
          }
        }
      }, false, true);
    }
    finally {
      consumer.finished();
    }
  }

  public List<SvnChangeList> getCommittedChanges(ChangeBrowserSettings settings, final RepositoryLocation location, final int maxCount) throws VcsException {
    final SvnRepositoryLocation svnLocation = (SvnRepositoryLocation) location;
    final ArrayList<SvnChangeList> result = new ArrayList<SvnChangeList>();
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    if (progress != null) {
      progress.setText(SvnBundle.message("progress.text.changes.collecting.changes"));
      progress.setText2(SvnBundle.message("progress.text2.changes.establishing.connection", location));
    }

    final String repositoryRoot;
    SVNRepository repository = null;
    try {
      repository = myVcs.createRepository(svnLocation.getURL());
      repositoryRoot = repository.getRepositoryRoot(true).toString();
      repository.closeSession();
    }
    catch (SVNException e) {
      throw new VcsException(e);
    } finally {
      if (repository != null) {
        repository.closeSession();
      }
    }

    getCommittedChangesImpl(settings, svnLocation.getURL(), new String[]{""}, maxCount, new Consumer<SVNLogEntry>() {
      public void consume(final SVNLogEntry svnLogEntry) {
        result.add(new SvnChangeList(myVcs, svnLocation, svnLogEntry, repositoryRoot));
      }
    }, false, true);
    settings.filterChanges(result);
    return result;
  }

  public void getCommittedChangesWithMergedRevisons(final ChangeBrowserSettings settings,
                                                                   final RepositoryLocation location, final int maxCount,
                                                                   final PairConsumer<SvnChangeList, TreeStructureNode<SVNLogEntry>> finalConsumer)
    throws VcsException {
    final SvnRepositoryLocation svnLocation = (SvnRepositoryLocation) location;
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    if (progress != null) {
      progress.setText(SvnBundle.message("progress.text.changes.collecting.changes"));
      progress.setText2(SvnBundle.message("progress.text2.changes.establishing.connection", location));
    }

    final String repositoryRoot;
    SVNRepository repository = null;
    try {
      repository = myVcs.createRepository(svnLocation.getURL());
      repositoryRoot = repository.getRepositoryRoot(true).toString();
    }
    catch (SVNException e) {
      throw new VcsException(e);
    } finally {
      if (repository != null) {
        repository.closeSession();
      }
    }

    final MergeTrackerProxy proxy = new MergeTrackerProxy(new Consumer<TreeStructureNode<SVNLogEntry>>() {
      public void consume(TreeStructureNode<SVNLogEntry> node) {
        finalConsumer.consume(new SvnChangeList(myVcs, svnLocation, node.getMe(), repositoryRoot), node);
      }
    });
    final SvnMergeSourceTracker mergeSourceTracker = new SvnMergeSourceTracker(new ThrowableConsumer<Pair<SVNLogEntry, Integer>, SVNException>() {
      public void consume(Pair<SVNLogEntry, Integer> svnLogEntryIntegerPair) throws SVNException {
        proxy.consume(svnLogEntryIntegerPair);
      }
    });

    getCommittedChangesImpl(settings, svnLocation.getURL(), new String[]{""}, maxCount, new Consumer<SVNLogEntry>() {
      public void consume(final SVNLogEntry svnLogEntry) {
        try {
          mergeSourceTracker.consume(svnLogEntry);
        }
        catch (SVNException e) {
          throw new RuntimeException(e);
          // will not occur actually but anyway never eat them
        }
      }
    }, true, false);
    
    proxy.finish();
  }


  private static class MergeTrackerProxy implements ThrowableConsumer<Pair<SVNLogEntry, Integer>, SVNException> {
    private TreeStructureNode<SVNLogEntry> myCurrentHierarchy;
    private final Consumer<TreeStructureNode<SVNLogEntry>> myConsumer;

    private MergeTrackerProxy(Consumer<TreeStructureNode<SVNLogEntry>> consumer) {
      myConsumer = consumer;
    }

    public void consume(Pair<SVNLogEntry, Integer> svnLogEntryIntegerPair) throws SVNException {
      final SVNLogEntry logEntry = svnLogEntryIntegerPair.getFirst();
      final Integer mergeLevel = svnLogEntryIntegerPair.getSecond();

      if (mergeLevel < 0) {
        if (myCurrentHierarchy != null) {
          myConsumer.consume(myCurrentHierarchy);
        }
        if (logEntry.hasChildren()) {
          myCurrentHierarchy = new TreeStructureNode<SVNLogEntry>(logEntry);
        } else {
          // just pass
          myCurrentHierarchy = null;
          myConsumer.consume(new TreeStructureNode<SVNLogEntry>(logEntry));
        }
      } else {
        addToLevel(myCurrentHierarchy, logEntry, mergeLevel);
      }
    }

    public void finish() {
      if (myCurrentHierarchy != null) {
        myConsumer.consume(myCurrentHierarchy);
      }
    }

    private static void addToLevel(final TreeStructureNode<SVNLogEntry> tree, final SVNLogEntry entry, final int left) {
      assert tree != null;
      if (left == 0) {
        tree.add(entry);
      } else {
        final List<TreeStructureNode<SVNLogEntry>> children = tree.getChildren();
        assert ! children.isEmpty();
        addToLevel(children.get(children.size() - 1), entry, left - 1);
      }
    }
  }

  private void getCommittedChangesImpl(ChangeBrowserSettings settings, final String url, final String[] filterUrls,
                                       final int maxCount, final Consumer<SVNLogEntry> resultConsumer, final boolean includeMergedRevisions,
                                       final boolean filterOutByDate) throws VcsException {
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    if (progress != null) {
      progress.setText(SvnBundle.message("progress.text.changes.collecting.changes"));
      progress.setText2(SvnBundle.message("progress.text2.changes.establishing.connection", url));
    }
    try {
      SVNLogClient logger = myVcs.createLogClient();

      final String author = settings.getUserFilter();
      final Date dateFrom = settings.getDateAfterFilter();
      final Long changeFrom = settings.getChangeAfterFilter();
      final Date dateTo = settings.getDateBeforeFilter();
      final Long changeTo = settings.getChangeBeforeFilter();

      final SVNRevision revisionBefore;
      if (dateTo != null) {
        revisionBefore = SVNRevision.create(dateTo);
      }
      else if (changeTo != null) {
        revisionBefore = SVNRevision.create(changeTo.longValue());
      }
      else {
        SVNRepository repository = null;
        final long revision;
        try {
          repository = myVcs.createRepository(url);
          revision = repository.getLatestRevision();
        } finally {
          if (repository != null) {
            repository.closeSession();
          }
        }
        revisionBefore = SVNRevision.create(revision);
      }
      final SVNRevision revisionAfter;
      if (dateFrom != null) {
        revisionAfter = SVNRevision.create(dateFrom);
      }
      else if (changeFrom != null) {
        revisionAfter = SVNRevision.create(changeFrom.longValue());
      }
      else {
        revisionAfter = SVNRevision.create(1);
      }

      logger.doLog(SVNURL.parseURIEncoded(url), filterUrls, revisionBefore, revisionBefore, revisionAfter,
                   settings.STOP_ON_COPY, true, includeMergedRevisions, maxCount, null,
                   new ISVNLogEntryHandler() {
                     public void handleLogEntry(SVNLogEntry logEntry) {
                       if (myProject.isDisposed()) throw new ProcessCanceledException();
                       if (progress != null) {
                         progress.setText2(SvnBundle.message("progress.text2.processing.revision", logEntry.getRevision()));
                         progress.checkCanceled();
                       }
                       if (filterOutByDate && logEntry.getDate() == null) {
                         // do not add lists without info - this situation is possible for lists where there are paths that user has no rights to observe
                         return;
                       }
                       if (author == null || author.equalsIgnoreCase(logEntry.getAuthor())) {
                         resultConsumer.consume(logEntry);
                       }
                     }
                   });
    }
    catch (SVNException e) {
      throw new VcsException(e);
    }
  }

  public ChangeListColumn[] getColumns() {
    return new ChangeListColumn[] {
      new ChangeListColumn.ChangeListNumberColumn(SvnBundle.message("revision.title")),
      ChangeListColumn.NAME, ChangeListColumn.DATE, ChangeListColumn.DESCRIPTION
    };
  }

  private void refreshMergeInfo(final RootsAndBranches action) {
    if (myMergeInfoUpdatesListener == null) {
      myMergeInfoUpdatesListener = new MergeInfoUpdatesListener(myProject, myConnection);
    }
    myMergeInfoUpdatesListener.addPanel(action);
  }

  private static class ShowHideMergePanel extends ToggleAction {
    private final DecoratorManager myManager;
    private final ChangeListFilteringStrategy myStrategy;
    private boolean myIsSelected;

    public ShowHideMergePanel(final DecoratorManager manager, final ChangeListFilteringStrategy strategy) {
      myManager = manager;
      myStrategy = strategy;
    }

    @Override
    public void update(final AnActionEvent e) {
      super.update(e);
      final Presentation presentation = e.getPresentation();
      presentation.setIcon(SvnIcons.ShowIntegratedFrom);
      presentation.setText(SvnBundle.message("committed.changes.action.enable.merge.highlighting"));
      presentation.setDescription(SvnBundle.message("committed.changes.action.enable.merge.highlighting.description.text"));
    }

    public boolean isSelected(final AnActionEvent e) {
      return myIsSelected;
    }

    public void setSelected(final AnActionEvent e, final boolean state) {
      myIsSelected = state;
      if (state) {
        myManager.setFilteringStrategy(myStrategy);
      } else {
        myManager.removeFilteringStrategy(myStrategy.getKey());
      }
    }
  }

  @Nullable
  public VcsCommittedViewAuxiliary createActions(final DecoratorManager manager, @Nullable final RepositoryLocation location) {
    final RootsAndBranches rootsAndBranches = new RootsAndBranches(myProject, manager, location);
    refreshMergeInfo(rootsAndBranches);

    final DefaultActionGroup popup = new DefaultActionGroup(myVcs.getDisplayName(), true);
    popup.add(rootsAndBranches.getIntegrateAction());
    popup.add(rootsAndBranches.getUndoIntegrateAction());
    popup.add(new ConfigureBranchesAction());

    final ShowHideMergePanel action = new ShowHideMergePanel(manager, rootsAndBranches.getStrategy());

    return new VcsCommittedViewAuxiliary(Collections.<AnAction>singletonList(popup), new Runnable() {
      public void run() {
        if (myMergeInfoUpdatesListener != null) {
          myMergeInfoUpdatesListener.removePanel(rootsAndBranches);
          rootsAndBranches.dispose();
        }
      }
    }, Collections.<AnAction>singletonList(action));
  }

  public int getUnlimitedCountValue() {
    return 0;
  }

  @Override
  public Pair<SvnChangeList, FilePath> getOneList(final VirtualFile file, VcsRevisionNumber number) throws VcsException {
    final RootUrlInfo rootUrlInfo = myVcs.getSvnFileUrlMapping().getWcRootForFilePath(new File(file.getPath()));
    if (rootUrlInfo == null) return null;
    final VirtualFile root = rootUrlInfo.getVirtualFile();
    if (root == null) return null;
    final SvnRepositoryLocation svnRootLocation = (SvnRepositoryLocation)getLocationFor(new FilePathImpl(root));
    if (svnRootLocation == null) return null;
    final String url = svnRootLocation.getURL();
    final long revision;
    try {
      revision = Long.parseLong(number.asString());
    } catch (NumberFormatException e) {
      throw new VcsException(e);
    }

    final SvnChangeList[] result = new SvnChangeList[1];
    final SVNLogClient logger;
    final SVNRevision revisionBefore;
    final SVNURL repositoryUrl;
    final SVNURL svnurl;
    final SVNInfo targetInfo;
    try {
      logger = myVcs.createLogClient();
      revisionBefore = SVNRevision.create(revision);

      svnurl = SVNURL.parseURIEncoded(url);
      final SVNWCClient client = myVcs.createWCClient();
      final SVNInfo info = client.doInfo(svnurl, SVNRevision.UNDEFINED, SVNRevision.HEAD);
      targetInfo = client.doInfo(new File(file.getPath()), SVNRevision.UNDEFINED);
      if (info == null) {
        throw new VcsException("Can not get repository URL");
      }
      repositoryUrl = info.getRepositoryRootURL();
    }
    catch (SVNException e) {
      LOG.info(e);
      throw new VcsException(e);
    }

    tryExactHit(svnRootLocation, result, logger, revisionBefore, repositoryUrl, svnurl);
    if (result[0] == null) {
      tryByRoot(result, logger, revisionBefore, repositoryUrl);
      if (result[0] == null) {
        FilePath path = tryStepByStep(svnRootLocation, result, logger, revisionBefore, targetInfo, svnurl);
        path = path == null ? new FilePathImpl(file) : path;
        // and pass & take rename context there
        return new Pair<SvnChangeList, FilePath>(result[0], path);
      }
    }
    if (result[0].getChanges().size() == 1) {
      final Collection<Change> changes = result[0].getChanges();
      final Change change = changes.iterator().next();
      final ContentRevision afterRevision = change.getAfterRevision();
      if (afterRevision != null) {
        return new Pair<SvnChangeList, FilePath>(result[0], afterRevision.getFile());
      } else {
        return new Pair<SvnChangeList, FilePath>(result[0], new FilePathImpl(file));
      }
    }
    String relativePath = SVNPathUtil.getRelativePath(targetInfo.getRepositoryRootURL().toString(), targetInfo.getURL().toString());
    relativePath = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
    final Change targetChange = result[0].getByPath(relativePath);
    if (targetChange == null) {
      FilePath path = tryStepByStep(svnRootLocation, result, logger, revisionBefore, targetInfo, svnurl);
      path = path == null ? new FilePathImpl(file) : path;
      // and pass & take rename context there
      return new Pair<SvnChangeList, FilePath>(result[0], path);
    }
    return new Pair<SvnChangeList, FilePath>(result[0], new FilePathImpl(file));
  }

  @Override
  public RepositoryLocation getForNonLocal(VirtualFile file) {
    final String url = file.getPresentableUrl();
    return new SvnRepositoryLocation(FileUtil.toSystemIndependentName(url));
  }

  @Override
  public boolean supportsIncomingChanges() {
    return true;
  }

  private static class RenameContext {
    @NotNull
    private String myCurrentPath;
    private String myRepositoryRoot;
    private boolean myHadChanged;

    private RenameContext(final SVNInfo info) {
      myRepositoryRoot = info.getRepositoryRootURL().toString();
      myCurrentPath = SVNPathUtil.getRelativePath(myRepositoryRoot, info.getURL().toString());
      myCurrentPath = myCurrentPath.startsWith("/") ? myCurrentPath : ("/" + myCurrentPath);
    }

    public void accept(final SVNLogEntry entry) {
      final Map changedPaths = entry.getChangedPaths();
      if (changedPaths == null) return;

      for (Object o : changedPaths.values()) {
        final SVNLogEntryPath entryPath = (SVNLogEntryPath) o;
        if (entryPath != null && 'A' == entryPath.getType() && entryPath.getCopyPath() != null) {
          if (myCurrentPath.equals(entryPath.getPath())) {
            myHadChanged = true;
            myCurrentPath = entryPath.getCopyPath();
            return;
          } else if (SVNPathUtil.isAncestor(entryPath.getPath(), myCurrentPath)) {
            final String relativePath = SVNPathUtil.getRelativePath(entryPath.getPath(), myCurrentPath);
            myCurrentPath = SVNPathUtil.append(entryPath.getCopyPath(), relativePath);
            myHadChanged = true;
            return;
          }
        }
      }
    }

    @Nullable
    public FilePath getFilePath(final SvnVcs vcs) {
      if (! myHadChanged) return null;
      final SvnFileUrlMapping svnFileUrlMapping = vcs.getSvnFileUrlMapping();
      final String absolutePath = SVNPathUtil.append(myRepositoryRoot, myCurrentPath);
      final String localPath = svnFileUrlMapping.getLocalPath(absolutePath);
      if (localPath == null) {
        LOG.info("Cannot find local path for url: " + absolutePath);
        return null;
      }
      return new FilePathImpl(new File(localPath), false);
    }
  }

  private void tryByRoot(SvnChangeList[] result, SVNLogClient logger, SVNRevision revisionBefore, SVNURL repositoryUrl) throws VcsException {
    final boolean authorized = SvnAuthenticationNotifier.passiveValidation(myProject, repositoryUrl);
    if (! authorized) return;
    tryExactHit(new SvnRepositoryLocation(repositoryUrl.toString()), result, logger, revisionBefore, repositoryUrl, repositoryUrl);
  }

  // return changed path, if any
  private FilePath tryStepByStep(final SvnRepositoryLocation svnRepositoryLocation,
                             final SvnChangeList[] result,
                             SVNLogClient logger,
                             final SVNRevision revisionBefore, final SVNInfo info, SVNURL svnurl) throws VcsException {
    final String repositoryRoot = info.getRepositoryRootURL().toString();
    try {
      final RenameContext renameContext = new RenameContext(info);
      logger.doLog(svnurl, null, SVNRevision.UNDEFINED, SVNRevision.HEAD, revisionBefore,
                   false, true, false, 0, null,
                   new ISVNLogEntryHandler() {
                     public void handleLogEntry(SVNLogEntry logEntry) {
                       if (myProject.isDisposed()) throw new ProcessCanceledException();
                       if (logEntry.getDate() == null) {
                         // do not add lists without info - this situation is possible for lists where there are paths that user has no rights to observe
                         return;
                       }
                       renameContext.accept(logEntry);
                       if (logEntry.getRevision() == revisionBefore.getNumber()) {
                         result[0] = new SvnChangeList(myVcs, svnRepositoryLocation, logEntry, repositoryRoot);
                       }
                     }
                   });
      return renameContext.getFilePath(myVcs);
    }
    catch (SVNException e) {
      LOG.info(e);
      throw new VcsException(e);
    }
  }

  private void tryExactHit(final SvnRepositoryLocation location,
                           final SvnChangeList[] result,
                           SVNLogClient logger,
                           SVNRevision revisionBefore,
                           final SVNURL repositoryUrl, SVNURL svnurl) throws VcsException {
    try {
      logger.doLog(svnurl, null, SVNRevision.UNDEFINED, revisionBefore, revisionBefore,
                   false, true, false, 1, null,
                   new ISVNLogEntryHandler() {
                     public void handleLogEntry(SVNLogEntry logEntry) {
                       if (myProject.isDisposed()) throw new ProcessCanceledException();
                       if (logEntry.getDate() == null) {
                         // do not add lists without info - this situation is possible for lists where there are paths that user has no rights to observe
                         return;
                       }
                       result[0] = new SvnChangeList(myVcs, location, logEntry, repositoryUrl.toString());
                     }
                   });
    }
    catch (SVNException e) {
      LOG.info(e);
      if (SVNErrorCode.FS_CATEGORY == e.getErrorMessage().getErrorCode().getCategory()) {
        // pass to step by step looking for revision
        return;
      }
      throw new VcsException(e);
    }
  }

  public int getFormatVersion() {
    return VERSION_WITH_REPLACED_PATHS;
  }

  public void writeChangeList(final DataOutput dataStream, final SvnChangeList list) throws IOException {
    list.writeToStream(dataStream);
  }

  public SvnChangeList readChangeList(final RepositoryLocation location, final DataInput stream) throws IOException {
    final int version = getFormatVersion();
    return new SvnChangeList(myVcs, (SvnRepositoryLocation) location, stream,
                             VERSION_WITH_COPY_PATHS_ADDED <= version, VERSION_WITH_REPLACED_PATHS <= version);  
  }

  public boolean isMaxCountSupported() {
    return true;
  }

  public Collection<FilePath> getIncomingFiles(final RepositoryLocation location) {
    return null;
  }

  public boolean refreshCacheByNumber() {
    return true;
  }

  public String getChangelistTitle() {
    return SvnBundle.message("changes.browser.revision.term");
  }

  public boolean isChangeLocallyAvailable(FilePath filePath, @Nullable VcsRevisionNumber localRevision, VcsRevisionNumber changeRevision,
                                          final SvnChangeList changeList) {
    return localRevision != null && localRevision.compareTo(changeRevision) >= 0;
  }

  public boolean refreshIncomingWithCommitted() {
    return true;
  }

  public void deactivate() {
    myConnection.disconnect();
  }
}
