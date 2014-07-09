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
package org.zmlx.hg4idea.action;

import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.ui.NewBranchAction;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.zmlx.hg4idea.HgRevisionNumber;
import org.zmlx.hg4idea.HgVcs;
import org.zmlx.hg4idea.HgVcsMessages;
import org.zmlx.hg4idea.command.HgBookmarkCreateCommand;
import org.zmlx.hg4idea.command.HgBranchCreateCommand;
import org.zmlx.hg4idea.command.HgMergeCommand;
import org.zmlx.hg4idea.command.HgUpdateCommand;
import org.zmlx.hg4idea.execution.HgCommandException;
import org.zmlx.hg4idea.execution.HgCommandResult;
import org.zmlx.hg4idea.execution.HgCommandResultHandler;
import org.zmlx.hg4idea.provider.update.HgConflictResolver;
import org.zmlx.hg4idea.provider.update.HgHeadMerger;
import org.zmlx.hg4idea.repo.HgRepository;
import org.zmlx.hg4idea.ui.HgBookmarkDialog;
import org.zmlx.hg4idea.util.HgErrorUtil;
import org.zmlx.hg4idea.util.HgUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Nadya Zabrodina
 */
public class HgBranchPopupActions {

  private final Project myProject;
  private final HgRepository myRepository;

  HgBranchPopupActions(Project project, HgRepository repository) {
    myProject = project;
    myRepository = repository;
  }

  ActionGroup createActions(@Nullable DefaultActionGroup toInsert) {
    DefaultActionGroup popupGroup = new DefaultActionGroup(null, false);
    popupGroup.addAction(new HgNewBranchAction(myProject, Collections.singletonList(myRepository), myRepository.getRoot()));
    popupGroup.addAction(new HgNewBookmarkAction(myProject, Collections.singletonList(myRepository), myRepository.getRoot()));

    if (toInsert != null) {
      popupGroup.addAll(toInsert);
    }

    popupGroup.addSeparator("Bookmarks");
    List<String> bookmarks = new ArrayList<String>(myRepository.getBookmarks());
    String currentBookmark = myRepository.getCurrentBookmark();
    Collections.sort(bookmarks);
    for (String bookmark : bookmarks) {
      AnAction bookmarkAction = new BranchActions(myProject, bookmark, myRepository);
      if (bookmark.equals(currentBookmark)) {
        bookmarkAction.getTemplatePresentation().setIcon(PlatformIcons.CHECK_ICON);
      }
      popupGroup.add(bookmarkAction);
    }

    popupGroup.addSeparator("Branches");
    List<String> branches = new ArrayList<String>(myRepository.getBranches());
    Collections.sort(branches);
    for (String branch : branches) {
      if (!branch.equals(myRepository.getCurrentBranch())) { // don't show current branch in the list
        popupGroup.add(new BranchActions(myProject, branch, myRepository));
      }
    }
    return popupGroup;
  }

  private static class HgNewBranchAction extends NewBranchAction<HgRepository> {
    @NotNull final VirtualFile myPreselectedRepo;

    HgNewBranchAction(@NotNull Project project, @NotNull List<HgRepository> repositories, @NotNull VirtualFile preselectedRepo) {
      super(project, repositories);
      myPreselectedRepo = preselectedRepo;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      final String name = HgUtil.getNewBranchNameFromUser(myProject, "Create New Branch");
      if (name == null) {
        return;
      }
      try {
        new HgBranchCreateCommand(myProject, myPreselectedRepo, name).execute(new HgCommandResultHandler() {
          @Override
          public void process(@Nullable HgCommandResult result) {
            myProject.getMessageBus().syncPublisher(HgVcs.BRANCH_TOPIC).update(myProject, null);
            if (HgErrorUtil.hasErrorsInCommandExecution(result)) {
              new HgCommandResultNotifier(myProject)
                .notifyError(result, "Creation failed", "Branch creation [" + name + "] failed");
            }
          }
        });
      }
      catch (HgCommandException exception) {
        HgAbstractGlobalAction.handleException(myProject, "Can't create new branch: ", exception);
      }
    }
  }

  private static class HgNewBookmarkAction extends DumbAwareAction {
    protected final List<HgRepository> myRepositories;
    protected Project myProject;
    @NotNull final VirtualFile myPreselectedRepo;

    HgNewBookmarkAction(@NotNull Project project, @NotNull List<HgRepository> repositories, @NotNull VirtualFile preselectedRepo) {
      super("New Bookmark", "Create new bookmark", null);
      myProject = project;
      myRepositories = repositories;
      myPreselectedRepo = preselectedRepo;
    }

    @Override
    public void update(AnActionEvent e) {
      if (DvcsUtil.anyRepositoryIsFresh(myRepositories)) {
        e.getPresentation().setEnabled(false);
        e.getPresentation().setDescription("Bookmark creation is not possible before the first commit.");
      }
    }

    @Override
    public void actionPerformed(AnActionEvent e) {

      final HgBookmarkDialog bookmarkDialog = new HgBookmarkDialog(myProject);
      bookmarkDialog.show();
      if (bookmarkDialog.isOK()) {
        try {
          final String name = bookmarkDialog.getName();
          new HgBookmarkCreateCommand(myProject, myPreselectedRepo, name,
                                      bookmarkDialog.isActive()).execute(new HgCommandResultHandler() {
            @Override
            public void process(@Nullable HgCommandResult result) {
              myProject.getMessageBus().syncPublisher(HgVcs.BRANCH_TOPIC).update(myProject, null);
              if (HgErrorUtil.hasErrorsInCommandExecution(result)) {
                new HgCommandResultNotifier(myProject)
                  .notifyError(result, "Creation failed", "Bookmark creation [" + name + "] failed");
              }
            }
          });
        }
        catch (HgCommandException exception) {
          HgAbstractGlobalAction.handleException(myProject, exception);
        }
      }
    }
  }


  /**
   * Actions available for  branches.
   */
  static class BranchActions extends ActionGroup {

    private final Project myProject;
    private String myBranchName;
    @NotNull private final HgRepository mySelectedRepository;

    BranchActions(@NotNull Project project, @NotNull String branchName,
                  @NotNull HgRepository selectedRepository) {
      super("", true);
      myProject = project;
      myBranchName = branchName;
      mySelectedRepository = selectedRepository;
      getTemplatePresentation().setText(calcBranchText(), false); // no mnemonics
    }

    @NotNull
    private String calcBranchText() {
      return myBranchName;
    }

    @NotNull
    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
      return new AnAction[]{
        new UpdateToAction(myProject, mySelectedRepository, myBranchName),
        new MergeAction(myProject, mySelectedRepository, myBranchName)
      };
    }

    private static class MergeAction extends DumbAwareAction {

      private final Project myProject;
      private final HgRepository mySelectedRepository;
      private final String myBranchName;

      public MergeAction(@NotNull Project project,
                         @NotNull HgRepository selectedRepository,
                         @NotNull String branchName) {
        super("Merge");
        myProject = project;
        mySelectedRepository = selectedRepository;
        myBranchName = branchName;
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        final UpdatedFiles updatedFiles = UpdatedFiles.create();
        final HgMergeCommand hgMergeCommand = new HgMergeCommand(myProject, mySelectedRepository.getRoot());
        hgMergeCommand.setBranch(myBranchName);
        final HgCommandResultNotifier notifier = new HgCommandResultNotifier(myProject);
        new Task.Backgroundable(myProject, "Merging changes...") {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            try {
              new HgHeadMerger(myProject, hgMergeCommand)
                .merge(mySelectedRepository.getRoot(), updatedFiles, HgRevisionNumber.NULL_REVISION_NUMBER);
              new HgConflictResolver(myProject, updatedFiles).resolve(mySelectedRepository.getRoot());
            }

            catch (VcsException exception) {
              if (exception.isWarning()) {
                notifier.notifyWarning("Warning during merge", exception.getMessage());
              }
              else {
                notifier.notifyError(null, "Exception during merge", exception.getMessage());
              }
            }
            catch (Exception e1) {
              HgAbstractGlobalAction.handleException(myProject, e1);
            }
          }
        }.queue();
      }
    }

    private static class UpdateToAction extends DumbAwareAction {

      @NotNull private final Project myProject;
      @NotNull private final HgRepository mySelectedRepository;
      @NotNull private final String myBranch;

      public UpdateToAction(@NotNull Project project,
                            @NotNull HgRepository selectedRepository,
                            @NotNull String branch) {
        super("Update To");
        myProject = project;
        mySelectedRepository = selectedRepository;
        myBranch = branch;
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        final VirtualFile repository = mySelectedRepository.getRoot();
        final HgUpdateCommand hgUpdateCommand = new HgUpdateCommand(myProject, repository);
        hgUpdateCommand.setBranch(myBranch);
        new Task.Backgroundable(myProject, HgVcsMessages.message("action.hg4idea.updateTo.description", myBranch)) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            HgCommandResult result = hgUpdateCommand.execute();
            assert myProject != null;  // myProject couldn't be null, see annotation for updateTo action
            if (HgErrorUtil.hasErrorsInCommandExecution(result)) {
              new HgCommandResultNotifier(myProject).notifyError(result, "", "Update failed");
              new HgConflictResolver(myProject).resolve(repository);
            }
            myProject.getMessageBus().syncPublisher(HgVcs.BRANCH_TOPIC).update(myProject, null);
          }
        }.queue();
      }
    }
  }
}
