/*
* Copyright 2000-2009 JetBrains s.r.o.
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

package com.intellij.find.replaceInProject;

import com.intellij.find.*;
import com.intellij.find.findInProject.FindInProjectManager;
import com.intellij.find.impl.FindInProjectUtil;
import com.intellij.notification.NotificationGroup;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Factory;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.*;
import com.intellij.usages.rules.UsageInFile;
import com.intellij.util.AdapterProcessor;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.*;

public class ReplaceInProjectManager {
  static final NotificationGroup NOTIFICATION_GROUP = NotificationGroup.toolWindowGroup("FindInPath", ToolWindowId.FIND, false);

  private final Project myProject;
  private boolean myIsFindInProgress = false;

  public static ReplaceInProjectManager getInstance(Project project) {
    return ServiceManager.getService(project, ReplaceInProjectManager.class);
  }

  public ReplaceInProjectManager(Project project) {
    myProject = project;
  }

  public static boolean hasReadOnlyUsages(final Collection<Usage> usages) {
    for (Usage usage : usages) {
      if (usage.isReadOnly()) return true;
    }

    return false;
  }

  static class ReplaceContext {
    private final UsageView usageView;
    private final FindModel findModel;
    private Set<Usage> excludedSet;

    ReplaceContext(@NotNull UsageView usageView, @NotNull FindModel findModel) {
      this.usageView = usageView;
      this.findModel = findModel;
    }

    @NotNull
    public FindModel getFindModel() {
      return findModel;
    }

    @NotNull
    public UsageView getUsageView() {
      return usageView;
    }

    @NotNull
    public Set<Usage> getExcludedSet() {
      if (excludedSet == null) excludedSet = usageView.getExcludedUsages();
      return excludedSet;
    }
  }

  public void replaceInProject(DataContext dataContext) {
    final FindManager findManager = FindManager.getInstance(myProject);
    final FindModel findModel = (FindModel)findManager.getFindInProjectModel().clone();
    findModel.setReplaceState(true);
    FindInProjectUtil.setDirectoryName(findModel, dataContext);

    Editor editor = PlatformDataKeys.EDITOR.getData(dataContext);
    FindUtil.initStringToFindWithSelection(findModel, editor);

    findManager.showFindDialog(findModel, new Runnable() {
      @Override
      public void run() {
        final PsiDirectory psiDirectory = FindInProjectUtil.getPsiDirectory(findModel, myProject);
        if (!findModel.isProjectScope() &&
            psiDirectory == null &&
            findModel.getModuleName() == null &&
            findModel.getCustomScope() == null) {
          return;
        }

        UsageViewManager manager = UsageViewManager.getInstance(myProject);

        if (manager == null) return;
        findManager.getFindInProjectModel().copyFrom(findModel);
        final FindModel findModelCopy = (FindModel)findModel.clone();

        searchAndShowUsages(manager, new UsageSearcherFactory(findModelCopy, psiDirectory), findModelCopy, findManager);
      }
    });
  }

  public void searchAndShowUsages(@NotNull UsageViewManager manager,
                                  final Factory<UsageSearcher> usageSearcherFactory,
                                  final FindModel findModelCopy,
                                  final FindManager findManager) {
    final UsageViewPresentation presentation = FindInProjectUtil.setupViewPresentation(true, findModelCopy);
    final FindUsagesProcessPresentation processPresentation = FindInProjectUtil.setupProcessPresentation(myProject, true, presentation);

    searchAndShowUsages(manager, usageSearcherFactory, findModelCopy, presentation, processPresentation, findManager);
  }

  public void searchAndShowUsages(UsageViewManager manager,
                                  final Factory<UsageSearcher> usageSearcherFactory,
                                  final FindModel findModelCopy,
                                  UsageViewPresentation presentation,
                                  FindUsagesProcessPresentation processPresentation,
                                  final FindManager findManager) {
    final ReplaceContext[] context = new ReplaceContext[1];
    presentation.setMergeDupLinesAvailable(false);
    manager.searchAndShowUsages(new UsageTarget[]{new FindInProjectUtil.StringUsageTarget(findModelCopy.getStringToFind())},
                                usageSearcherFactory, processPresentation, presentation, new UsageViewManager.UsageViewStateListener() {
        @Override
        public void usageViewCreated(@NotNull UsageView usageView) {
          context[0] = new ReplaceContext(usageView, findModelCopy);
          addReplaceActions(context[0]);
        }

        @Override
        public void findingUsagesFinished(final UsageView usageView) {
          if (context[0] != null && findManager.getFindInProjectModel().isPromptOnReplace()) {
            SwingUtilities.invokeLater(new Runnable() {
              @Override
              public void run() {
                replaceWithPrompt(context[0]);
              }
            });
          }
        }
      });
  }

  private void replaceWithPrompt(final ReplaceContext replaceContext) {
    final List<Usage> _usages = replaceContext.getUsageView().getSortedUsages();

    if (hasReadOnlyUsages(_usages)) {
      WindowManager.getInstance().getStatusBar(myProject)
        .setInfo(FindBundle.message("find.replace.occurrences.found.in.read.only.files.status"));
      return;
    }

    final Usage[] usages = _usages.toArray(new Usage[_usages.size()]);

    //usageView.expandAll();
    for (int i = 0; i < usages.length; ++i) {
      final Usage usage = usages[i];
      final UsageInfo usageInfo = ((UsageInfo2UsageAdapter)usage).getUsageInfo();

      final PsiElement elt = usageInfo.getElement();
      if (elt == null) continue;
      final PsiFile psiFile = elt.getContainingFile();
      if (!psiFile.isWritable()) continue;

      Runnable selectOnEditorRunnable = new Runnable() {
        @Override
        public void run() {
          final VirtualFile virtualFile = psiFile.getVirtualFile();

          if (virtualFile != null && ApplicationManager.getApplication().runReadAction(new Computable<Boolean>() {
            @Override
            public Boolean compute() {
              return virtualFile.isValid() ? Boolean.TRUE : Boolean.FALSE;
            }
          }).booleanValue()) {

            if (usage.isValid()) {
              usage.highlightInEditor();
              replaceContext.getUsageView().selectUsages(new Usage[]{usage});
            }
          }
        }
      };

      CommandProcessor.getInstance()
        .executeCommand(myProject, selectOnEditorRunnable, FindBundle.message("find.replace.select.on.editor.command"), null);
      String title = FindBundle.message("find.replace.found.usage.title", i + 1, usages.length);

      int result;
      try {
        doReplace(usage, replaceContext.getFindModel(), replaceContext.getExcludedSet(), true);
        result = FindManager.getInstance(myProject).showPromptDialog(replaceContext.getFindModel(), title);
      }
      catch (FindManager.MalformedReplacementStringException e) {
        markAsMalformedReplacement(replaceContext, usage);
        result = FindManager.getInstance(myProject).showMalformedReplacementPrompt(replaceContext.getFindModel(), title, e);
      }

      if (result == FindManager.PromptResult.CANCEL) {
        return;
      }
      if (result == FindManager.PromptResult.SKIP) {
        continue;
      }

      final int currentNumber = i;
      if (result == FindManager.PromptResult.OK) {
        final Ref<Boolean> success = Ref.create();
        Runnable runnable = new Runnable() {
          @Override
          public void run() {
            success.set(doReplace(usage, replaceContext));
          }
        };
        CommandProcessor.getInstance().executeCommand(myProject, runnable, FindBundle.message("find.replace.command"), null);
        if (closeUsageViewIfEmpty(replaceContext.getUsageView(), success.get())) {
          return;
        }
      }

      if (result == FindManager.PromptResult.ALL_IN_THIS_FILE) {
        final int[] nextNumber = new int[1];

        Runnable runnable = new Runnable() {
          @Override
          public void run() {
            int j = currentNumber;
            boolean  success = true;
            for (; j < usages.length; j++) {
              final Usage usage = usages[j];
              final UsageInfo usageInfo = ((UsageInfo2UsageAdapter)usage).getUsageInfo();

              final PsiElement elt = usageInfo.getElement();
              if (elt == null) continue;
              PsiFile otherPsiFile = elt.getContainingFile();
              if (!otherPsiFile.equals(psiFile)) {
                break;
              }
              if (!doReplace(usage, replaceContext)) {
                success = false;
              }
            }
            closeUsageViewIfEmpty(replaceContext.getUsageView(), success);
            nextNumber[0] = j;
          }
        };

        CommandProcessor.getInstance().executeCommand(myProject, runnable, FindBundle.message("find.replace.command"), null);

        //noinspection AssignmentToForLoopParameter
        i = nextNumber[0] - 1;
      }

      if (result == FindManager.PromptResult.ALL_FILES) {
        CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
          @Override
          public void run() {
            final boolean success = doReplace(replaceContext, _usages);
            closeUsageViewIfEmpty(replaceContext.getUsageView(), success);
          }
        }, FindBundle.message("find.replace.command"), null);
        break;
      }
    }
  }

  private boolean doReplace(Usage usage, ReplaceContext replaceContext) {
    try {
      doReplace(usage, replaceContext.getFindModel(), replaceContext.getExcludedSet(), false);
      replaceContext.getUsageView().removeUsage(usage);
    }
    catch (FindManager.MalformedReplacementStringException e) {
      markAsMalformedReplacement(replaceContext, usage);
      return false;
    }
    return true;
  }

  private void addReplaceActions(final ReplaceContext replaceContext) {
    final Runnable replaceRunnable = new Runnable() {
      @Override
      public void run() {
        final UsageView usageView = replaceContext.getUsageView();
        final boolean success = doReplace(replaceContext, usageView.getUsages());
        closeUsageViewIfEmpty(usageView, success);
      }
    };
    replaceContext.getUsageView().addButtonToLowerPane(replaceRunnable, FindBundle.message("find.replace.all.action"));

    final Runnable replaceSelectedRunnable = new Runnable() {
      @Override
      public void run() {
        doReplaceSelected(replaceContext);
      }
    };

    replaceContext.getUsageView().addButtonToLowerPane(replaceSelectedRunnable, FindBundle.message("find.replace.selected.action"));
  }

  private boolean doReplace(final ReplaceContext replaceContext, Collection<Usage> usages) {
    boolean success = true;
    int replacedCount = 0;
    ensureUsagesWritable(usages);
    for (final Usage usage : usages) {
      try {
        doReplace(usage, replaceContext.getFindModel(), replaceContext.getExcludedSet(), false);
        replaceContext.getUsageView().removeUsage(usage);
        replacedCount++;
      }
      catch (FindManager.MalformedReplacementStringException e) {
        markAsMalformedReplacement(replaceContext, usage);
        success = false;
      }
    }
    reportNumberReplacedOccurrences(myProject, replacedCount);
    return success;
  }

  private static void markAsMalformedReplacement(ReplaceContext replaceContext, Usage usage) {
    replaceContext.getUsageView().excludeUsages(new Usage[]{usage});
  }

  public static void reportNumberReplacedOccurrences(Project project, int occurrences) {
    if (occurrences != 0) {
      final StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
      if (statusBar != null) {
        statusBar.setInfo(FindBundle.message("0.occurrences.replaced", occurrences));
      }
    }
  }

  public void doReplace(@NotNull final Usage usage,
                        @NotNull final FindModel findModel,
                        @NotNull final Set<Usage> excludedSet,
                        final boolean justCheck)
    throws FindManager.MalformedReplacementStringException {
    final Ref<FindManager.MalformedReplacementStringException> exceptionResult = Ref.create();
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        if (excludedSet.contains(usage)) {
          return;
        }

        final Document document = ((UsageInfo2UsageAdapter)usage).getDocument();
        if (!document.isWritable()) return;
        ((UsageInfo2UsageAdapter)usage).processRangeMarkers(new Processor<Segment>() {
          @Override
          public boolean process(Segment segment) {
            final int textOffset = segment.getStartOffset();
            final int textEndOffset = segment.getEndOffset();
            final Ref<String> stringToReplace = Ref.create();
            try {
              if (!getStringToReplace(textOffset, textEndOffset, document, findModel, stringToReplace)) return true;
              if (!stringToReplace.isNull() && !justCheck) {
                document.replaceString(textOffset, textEndOffset, stringToReplace.get());
              }
            }
            catch (FindManager.MalformedReplacementStringException e) {
              exceptionResult.set(e);
              return false;
            }
            return true;
          }
        });
      }
    });
    if (!exceptionResult.isNull()) {
      throw exceptionResult.get();
    }
  }


  private boolean getStringToReplace(int textOffset,
                                     int textEndOffset,
                                     Document document, FindModel findModel, Ref<String> stringToReplace)
    throws FindManager.MalformedReplacementStringException {
    if (textOffset < 0 || textOffset >= document.getTextLength()) {
      return false;
    }
    if (textEndOffset < 0 || textOffset > document.getTextLength()) {
      return false;
    }
    FindManager findManager = FindManager.getInstance(myProject);
    final CharSequence foundString = document.getCharsSequence().subSequence(textOffset, textEndOffset);
    FindResult findResult = findManager.findString(document.getCharsSequence(), textOffset, findModel);
    if (!findResult.isStringFound()) {
      return false;
    }

    stringToReplace.set(
      FindManager.getInstance(myProject).getStringToReplace(foundString.toString(), findModel, textOffset, document.getText()));

    return true;
  }

  private void doReplaceSelected(final ReplaceContext replaceContext) {
    final Set<Usage> selectedUsages = replaceContext.getUsageView().getSelectedUsages();
    if (selectedUsages == null) {
      return;
    }

    ensureUsagesWritable(selectedUsages);

    if (hasReadOnlyUsages(selectedUsages)) {
      int result = Messages.showOkCancelDialog(replaceContext.getUsageView().getComponent(),
                                               FindBundle.message("find.replace.occurrences.in.read.only.files.prompt"),
                                               FindBundle.message("find.replace.occurrences.in.read.only.files.title"),
                                               Messages.getWarningIcon());
      if (result != 0) {
        return;
      }
    }

    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      @Override
      public void run() {
        final boolean success = doReplace(replaceContext, selectedUsages);
        final UsageView usageView = replaceContext.getUsageView();

        if (closeUsageViewIfEmpty(usageView, success)) return;
        usageView.getComponent().requestFocus();
      }
    }, FindBundle.message("find.replace.command"), null);
  }

  private void ensureUsagesWritable(Collection<Usage> selectedUsages) {
    Set<VirtualFile> readOnlyFiles = null;
    for (final Usage usage : selectedUsages) {
      final VirtualFile file = ((UsageInFile)usage).getFile();

      if (file != null && !file.isWritable()) {
        if (readOnlyFiles == null) readOnlyFiles = new HashSet<VirtualFile>();
        readOnlyFiles.add(file);
      }
    }

    if (readOnlyFiles != null) {
      ReadonlyStatusHandler.getInstance(myProject).ensureFilesWritable(VfsUtilCore.toVirtualFileArray(readOnlyFiles));
    }
  }

  private boolean closeUsageViewIfEmpty(UsageView usageView, boolean success) {
    if (usageView.getUsages().isEmpty()) {
      usageView.close();
      return true;
    } else if (!success) {
      NOTIFICATION_GROUP.createNotification("One or more malformed replacement strings", MessageType.ERROR).notify(myProject);
    }
    return false;
  }

  public boolean isWorkInProgress() {
    return myIsFindInProgress;
  }

  public boolean isEnabled() {
    return !myIsFindInProgress && !FindInProjectManager.getInstance(myProject).isWorkInProgress();
  }

  private class UsageSearcherFactory implements Factory<UsageSearcher> {
    private final FindModel myFindModelCopy;
    private final PsiDirectory myPsiDirectory;

    public UsageSearcherFactory(FindModel findModelCopy, PsiDirectory psiDirectory) {
      myFindModelCopy = findModelCopy;
      myPsiDirectory = psiDirectory;
    }

    @Override
    public UsageSearcher create() {
      return new UsageSearcher() {

        @Override
        public void generate(final Processor<Usage> processor) {
          try {
            myIsFindInProgress = true;

            FindInProjectUtil.findUsages(myFindModelCopy, myPsiDirectory, myProject,
                                         true, new AdapterProcessor<UsageInfo, Usage>(processor, UsageInfo2UsageAdapter.CONVERTER));
          }
          finally {
            myIsFindInProgress = false;
          }
        }
      };
    }
  }
}
