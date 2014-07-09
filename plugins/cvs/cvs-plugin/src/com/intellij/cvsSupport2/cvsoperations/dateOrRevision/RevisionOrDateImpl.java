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
package com.intellij.cvsSupport2.cvsoperations.dateOrRevision;

import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.cvsSupport2.application.CvsEntriesManager;
import com.intellij.cvsSupport2.config.DateOrRevisionSettings;
import com.intellij.cvsSupport2.history.CvsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import org.netbeans.lib.cvsclient.admin.Entry;
import org.netbeans.lib.cvsclient.command.Command;
import org.jetbrains.annotations.NotNull;

/**
 * author: lesya
 */
public class RevisionOrDateImpl implements RevisionOrDate {
  private String myStickyTag;
  private String myStickyDate;

  public static RevisionOrDate createOn(@NotNull VirtualFile file) {
    VirtualFile parent = file.getParent();
    return new RevisionOrDateImpl(parent, CvsEntriesManager.getInstance().getEntryFor(parent, file.getName()));
  }

  public static RevisionOrDate createOn(VirtualFile parent, String name) {
    return new RevisionOrDateImpl(parent, CvsEntriesManager.getInstance().getEntryFor(parent, name));
  }

  public static RevisionOrDate createOn(VirtualFile parent, Entry entry, DateOrRevisionSettings config) {
    RevisionOrDateImpl result = new RevisionOrDateImpl(parent, entry);
    updateOn(result, config);
    return result;
  }

  private static void updateOn(RevisionOrDateImpl result, DateOrRevisionSettings config) {
    String stickyTagFromConfig = config.USE_BRANCH ? config.BRANCH : null;
    String stickyDateFromConfig = config.USE_DATE ? config.getDate() : null;
    result.setStickyInfo(stickyTagFromConfig, stickyDateFromConfig);
  }

  @NotNull
  public static RevisionOrDate createOn(DateOrRevisionSettings config) {
    RevisionOrDateImpl result = new RevisionOrDateImpl();
    updateOn(result, config);
    return result;
  }

  private RevisionOrDateImpl() {

  }

  private RevisionOrDateImpl(VirtualFile parent, Entry entry) {
    if (entry == null) {
      lookupDirectory(parent);
    }
    else {
      if (entry.getStickyRevision() != null) {
        myStickyTag = entry.getStickyRevision();
      }
      else if (entry.getStickyTag() != null) {
        myStickyTag = entry.getStickyTag();
      }
      else if (entry.getStickyDateString() != null) {
        myStickyDate = entry.getStickyDateString();
      }
      else {
        lookupDirectory(parent);
      }
    }
  }

  private void setStickyInfo(String stickyTag, String stickyDate) {
    if ((stickyTag == null) && (stickyDate == null)) return;
    if (stickyTag != null) {
      myStickyDate = null;
      myStickyTag = stickyTag;
    }
    else {
      myStickyTag = null;
      myStickyDate = stickyDate;
    }
  }

  public void setForCommand(Command command) {
    CommandWrapper wrapper = new CommandWrapper(command);
    wrapper.setUpdateByRevisionOrDate(myStickyTag, myStickyDate);
  }

  private void lookupDirectory(VirtualFile directory) {
    String stickyTag = CvsUtil.getStickyTagForDirectory(directory);
    if (stickyTag != null) {
      myStickyTag = stickyTag;
      return;
    }
    myStickyDate = CvsUtil.getStickyDateForDirectory(directory);
  }

  public String getRevision() {
    if (myStickyTag == null) {
      return "HEAD";
    }
    return myStickyTag;
  }

  public CvsRevisionNumber getCvsRevisionNumber() {
    if (myStickyTag == null) return null;
    try {
      return new CvsRevisionNumber(myStickyTag);
    }
    catch (NumberFormatException ex) {
      return null;
    }
  }

  public String toString() {
    if (myStickyDate != null) {
      return myStickyDate;
    } else {
      return myStickyTag;
    }
  }
}
