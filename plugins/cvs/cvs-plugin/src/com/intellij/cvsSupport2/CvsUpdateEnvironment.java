/*
 * Copyright (c) 2004 JetBrains s.r.o. All  Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * -Redistributions of source code must retain the above copyright
 *  notice, this list of conditions and the following disclaimer.
 *
 * -Redistribution in binary form must reproduct the above copyright
 *  notice, this list of conditions and the following disclaimer in
 *  the documentation and/or other materials provided with the distribution.
 *
 * Neither the name of JetBrains or IntelliJ IDEA
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING
 * ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. JETBRAINS AND ITS LICENSORS SHALL NOT
 * BE LIABLE FOR ANY DAMAGES OR LIABILITIES SUFFERED BY LICENSEE AS A RESULT
 * OF OR RELATING TO USE, MODIFICATION OR DISTRIBUTION OF THE SOFTWARE OR ITS
 * DERIVATIVES. IN NO EVENT WILL JETBRAINS OR ITS LICENSORS BE LIABLE FOR ANY LOST
 * REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL,
 * INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY
 * OF LIABILITY, ARISING OUT OF THE USE OF OR INABILITY TO USE SOFTWARE, EVEN
 * IF JETBRAINS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 *
 */
package com.intellij.cvsSupport2;

import com.intellij.cvsSupport2.actions.merge.CvsMergeProvider;
import com.intellij.cvsSupport2.actions.update.UpdateSettingsOnCvsConfiguration;
import com.intellij.cvsSupport2.config.CvsConfiguration;
import com.intellij.cvsSupport2.config.DateOrRevisionSettings;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback;
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler;
import com.intellij.cvsSupport2.cvshandlers.CvsHandler;
import com.intellij.cvsSupport2.cvshandlers.CvsUpdatePolicy;
import com.intellij.cvsSupport2.cvshandlers.UpdateHandler;
import com.intellij.cvsSupport2.updateinfo.UpdatedFilesProcessor;
import com.intellij.cvsSupport2.util.CvsVfsUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.cvsIntegration.CvsResult;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vcs.update.*;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CvsUpdateEnvironment implements UpdateEnvironment {
  private final Project myProject;
  private boolean myLastUpdateWasConfigured = false;

  public CvsUpdateEnvironment(Project project) {
    myProject = project;

  }

  public void fillGroups(UpdatedFiles updatedFiles) {
    CvsUpdatePolicy.fillGroups(updatedFiles);
  }

  private static class CvsSequentialUpdateContext implements SequentialUpdatesContext {
    private final UpdateSettingsOnCvsConfiguration myConfiguration;
    private final String myUpdateTagName;
    private final static String error = "merge.wasnt.started.only.update.was.performed";

    private CvsSequentialUpdateContext(final UpdateSettingsOnCvsConfiguration configuration, final String tagName) {
      myUpdateTagName = tagName;
      myConfiguration = configuration;
    }

    @NotNull
    public String getMessageWhenInterruptedBeforeStart() {
      String mergeString = "-j " + myConfiguration.getBranch1ToMergeWith();
      if (myConfiguration.getBranch2ToMergeWith() != null) {
        mergeString += " -j " + myConfiguration.getBranch2ToMergeWith();
      }
      return "Merge (" + mergeString + ") wasn't started, only update (-r " + myUpdateTagName + ") was performed";
    }

    public UpdateSettingsOnCvsConfiguration getConfiguration() {
      return myConfiguration;
    }
  }

  private UpdateSettingsOnCvsConfiguration createSettingsAndUpdateContext(final CvsConfiguration cvsConfiguration,
                                                                          @NotNull final Ref<SequentialUpdatesContext> contextRef) {
    if (contextRef.get() != null) {
      final CvsSequentialUpdateContext cvsContext = (CvsSequentialUpdateContext) contextRef.get();
      contextRef.set(null);
      return cvsContext.getConfiguration();
    }
    
    if ((! cvsConfiguration.CLEAN_COPY) && cvsConfiguration.UPDATE_DATE_OR_REVISION_SETTINGS.overridesDefault() &&
        (cvsConfiguration.MERGING_MODE != CvsConfiguration.DO_NOT_MERGE)) {
      // split into 2 updates
      final UpdateSettingsOnCvsConfiguration secondUpdate = new UpdateSettingsOnCvsConfiguration(
          cvsConfiguration.PRUNE_EMPTY_DIRECTORIES, cvsConfiguration.MERGING_MODE, cvsConfiguration.MERGE_WITH_BRANCH1_NAME,
          cvsConfiguration.MERGE_WITH_BRANCH2_NAME, cvsConfiguration.CREATE_NEW_DIRECTORIES, cvsConfiguration.UPDATE_KEYWORD_SUBSTITUTION,
          new DateOrRevisionSettings(), cvsConfiguration.MAKE_NEW_FILES_READONLY, cvsConfiguration.CLEAN_COPY, cvsConfiguration.RESET_STICKY);
      contextRef.set(new CvsSequentialUpdateContext(secondUpdate, cvsConfiguration.UPDATE_DATE_OR_REVISION_SETTINGS.asString()));

      return new UpdateSettingsOnCvsConfiguration(
          cvsConfiguration.PRUNE_EMPTY_DIRECTORIES, CvsConfiguration.DO_NOT_MERGE, null, null, cvsConfiguration.CREATE_NEW_DIRECTORIES,
          cvsConfiguration.UPDATE_KEYWORD_SUBSTITUTION, cvsConfiguration.UPDATE_DATE_OR_REVISION_SETTINGS,
          cvsConfiguration.MAKE_NEW_FILES_READONLY, cvsConfiguration.CLEAN_COPY, cvsConfiguration.RESET_STICKY);
    } else {
      // usual way
      return new UpdateSettingsOnCvsConfiguration(cvsConfiguration, cvsConfiguration.CLEAN_COPY, cvsConfiguration.RESET_STICKY);
    }
  }

  @NotNull
  public UpdateSession updateDirectories(@NotNull FilePath[] contentRoots, final UpdatedFiles updatedFiles, ProgressIndicator progressIndicator,
                                         @NotNull final Ref<SequentialUpdatesContext> contextRef) {
    CvsConfiguration cvsConfiguration = CvsConfiguration.getInstance(myProject);
    if (!myLastUpdateWasConfigured) {
      cvsConfiguration.CLEAN_COPY = false;
      cvsConfiguration.RESET_STICKY = false;
    }
    myLastUpdateWasConfigured = false;

    try {
      final UpdateSettingsOnCvsConfiguration updateSettings = createSettingsAndUpdateContext(cvsConfiguration, contextRef);
      final UpdateHandler handler = CommandCvsHandler.createUpdateHandler(contentRoots, updateSettings, myProject, updatedFiles);
      handler.addCvsListener(new UpdatedFilesProcessor(myProject, updatedFiles));
      CvsOperationExecutor cvsOperationExecutor = new CvsOperationExecutor(true, myProject, ModalityState.defaultModalityState());
      cvsOperationExecutor.setShowErrors(false);
      cvsOperationExecutor.performActionSync(handler, CvsOperationExecutorCallback.EMPTY);
      final CvsResult result = cvsOperationExecutor.getResult();
      return createUpdateSessionAdapter(updatedFiles, result);
    }
    finally {
      cvsConfiguration.CLEAN_COPY = false;
      cvsConfiguration.RESET_STICKY = false;                    
    }
  }

  private UpdateSessionAdapter createUpdateSessionAdapter(final UpdatedFiles updatedFiles, final CvsResult result) {
    return new UpdateSessionAdapter(result.getErrorsAndWarnings(), result.isCanceled()) {
      public void onRefreshFilesCompleted() {
        final FileGroup mergedWithConflictsGroup = updatedFiles.getGroupById(FileGroup.MERGED_WITH_CONFLICT_ID);
        final FileGroup binaryMergedGroup = updatedFiles.getGroupById(CvsUpdatePolicy.BINARY_MERGED_ID);
        if (!mergedWithConflictsGroup.isEmpty() || !binaryMergedGroup.isEmpty()) {
          Collection<String> paths = new ArrayList<String>();
          paths.addAll(mergedWithConflictsGroup.getFiles());
          paths.addAll(binaryMergedGroup.getFiles());

          final List<VirtualFile> list = invokeManualMerging(paths, myProject);
          FileGroup mergedGroup = updatedFiles.getGroupById(FileGroup.MERGED_ID);
          final VcsKey vcsKey = CvsVcs2.getKey();
          for(VirtualFile mergedFile: list) {
            String path = FileUtil.toSystemDependentName(mergedFile.getPresentableUrl());
            mergedWithConflictsGroup.remove(path);
            binaryMergedGroup.remove(path);
            mergedGroup.add(path, vcsKey, null);
          }
        }
      }
    };
  }

  private static List<VirtualFile> invokeManualMerging(Collection<String> paths, Project project) {
    final List<VirtualFile> readOnlyFiles = new ArrayList<VirtualFile>();
    final List<VirtualFile> files = new ArrayList<VirtualFile>();

    for (final String path : paths) {
      final VirtualFile virtualFile = CvsVfsUtil.findFileByIoFile(new File(path));
      if (virtualFile != null) {
        files.add(virtualFile);
        if (!virtualFile.isWritable()) {
          readOnlyFiles.add(virtualFile);
        }
      }
    }

    if (readOnlyFiles.size() > 0) {
      final CvsHandler editHandler = CommandCvsHandler.createEditHandler(readOnlyFiles.toArray(new VirtualFile[readOnlyFiles.size()]),
                                                                         CvsConfiguration.getInstance(project).RESERVED_EDIT);
      new CvsOperationExecutor(true, project, ModalityState.current()).performActionSync(editHandler, CvsOperationExecutorCallback.EMPTY);
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        public void run() {
          for(VirtualFile file: readOnlyFiles) {
            file.refresh(false, false);
          }
        }
      });
    }

    if (! files.isEmpty()) {
      return AbstractVcsHelper.getInstance(project).showMergeDialog(files, new CvsMergeProvider());
    }
    return Collections.emptyList();
  }


  public Configurable createConfigurable(Collection<FilePath> files) {
    myLastUpdateWasConfigured = true;
    CvsConfiguration.getInstance(myProject).CLEAN_COPY = false;
    CvsConfiguration.getInstance(myProject).RESET_STICKY = false;
    return new UpdateConfigurable(myProject, files);
  }

  public boolean validateOptions(final Collection<FilePath> roots) {
    return true;
  }
}
