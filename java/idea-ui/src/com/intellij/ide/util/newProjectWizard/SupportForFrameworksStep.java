
package com.intellij.ide.util.newProjectWizard;

import com.intellij.CommonBundle;
import com.intellij.framework.addSupport.FrameworkSupportInModuleProvider;
import com.intellij.ide.util.frameworkSupport.FrameworkSupportUtil;
import com.intellij.ide.util.newProjectWizard.impl.FrameworkSupportModelBase;
import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.ProjectBuilder;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.ide.wizard.CommitStepException;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ui.configuration.projectRoot.LibrariesContainer;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import javax.swing.tree.TreeNode;
import java.util.List;

/**
 * @author nik
 */
public class SupportForFrameworksStep extends ModuleWizardStep {
  private final AddSupportForFrameworksPanel mySupportForFrameworksPanel;
  private final FrameworkSupportModelBase myFrameworkSupportModel;
  private final WizardContext myContext;
  private final ModuleBuilder myBuilder;
  private final ModuleBuilder.ModuleConfigurationUpdater myConfigurationUpdater;
  private boolean myCommitted;

  public SupportForFrameworksStep(WizardContext context, final ModuleBuilder builder,
                                  @NotNull LibrariesContainer librariesContainer) {
    myContext = context;
    myBuilder = builder;
    List<FrameworkSupportInModuleProvider> providers = FrameworkSupportUtil.getProviders(builder);
    myFrameworkSupportModel = new FrameworkSupportModelInWizard(librariesContainer, builder);
    mySupportForFrameworksPanel = new AddSupportForFrameworksPanel(providers, myFrameworkSupportModel);
    myConfigurationUpdater = new ModuleBuilder.ModuleConfigurationUpdater() {
      public void update(@NotNull final Module module, @NotNull final ModifiableRootModel rootModel) {
        mySupportForFrameworksPanel.addSupport(module, rootModel);
      }
    };
    builder.addModuleConfigurationUpdater(myConfigurationUpdater);
  }

  private static String getBaseDirectory(final ModuleBuilder builder) {
    final String path = builder.getContentEntryPath();
    return path != null ? FileUtil.toSystemIndependentName(path) : "";
  }

  public Icon getIcon() {
    return ICON;
  }

  @Override
  public void disposeUIResources() {
    Disposer.dispose(mySupportForFrameworksPanel);
    super.disposeUIResources();
  }

  @NonNls
  public String getHelpId() {
    return "reference.dialogs.new.project.technologies";
  }

  public void _commit(final boolean finishChosen) throws CommitStepException {
    if (finishChosen && !myCommitted) {
      boolean ok = mySupportForFrameworksPanel.downloadLibraries();
      if (!ok) {
        int answer = Messages.showYesNoDialog(getComponent(),
                                              ProjectBundle.message("warning.message.some.required.libraries.wasn.t.downloaded"),
                                              CommonBundle.getWarningTitle(), Messages.getWarningIcon());
        if (answer != 0) {
          throw new CommitStepException(null);
        }
      }
      myCommitted = true;
    }
  }

  public JComponent getComponent() {
    return mySupportForFrameworksPanel.getMainPanel();
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return mySupportForFrameworksPanel.getFrameworksTree();
  }

  @Override
  public void updateStep() {
    ProjectBuilder builder = myContext.getProjectBuilder();
    if (builder instanceof ModuleBuilder) {
      myBuilder.updateFrom((ModuleBuilder)builder);
      ((ModuleBuilder)builder).addModuleConfigurationUpdater(myConfigurationUpdater);
    }
    myFrameworkSupportModel.fireWizardStepUpdated();
  }

  public void updateDataModel() {
  }

  @Override
  public String getName() {
    return "Add Frameworks";
  }

  private static class FrameworkSupportModelInWizard extends FrameworkSupportModelBase {
    private final ModuleBuilder myBuilder;

    public FrameworkSupportModelInWizard(LibrariesContainer librariesContainer, ModuleBuilder builder) {
      super(librariesContainer.getProject(), builder, librariesContainer);
      myBuilder = builder;
    }

    @NotNull
    @Override
    public String getBaseDirectoryForLibrariesPath() {
      return getBaseDirectory(myBuilder);
    }
  }

  @TestOnly
  public boolean enableSupport(final String frameworkId) {
    return !TreeUtil.traverse((TreeNode)mySupportForFrameworksPanel.getFrameworksTree().getModel().getRoot(), new TreeUtil.Traverse() {
      @Override
      public boolean accept(Object node) {
        if (node instanceof FrameworkSupportNode && frameworkId.equals(
          ((FrameworkSupportNode)node).getProvider().getFrameworkType().getId())) {
          TreeNode parent = ((FrameworkSupportNode)node).getParent();
          if (parent instanceof FrameworkSupportNode) {
            ((FrameworkSupportNode)parent).setChecked(true);
          }
          ((FrameworkSupportNode)node).setChecked(true);
          return false;
        }
        return true;
      }
    });
  }
}
