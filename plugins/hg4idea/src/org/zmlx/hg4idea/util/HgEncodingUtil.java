package org.zmlx.hg4idea.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;

import static org.zmlx.hg4idea.HgVcs.HGENCODING;

/**
 * @author Kirill Likhodedov
 */
public class HgEncodingUtil {

  @NotNull
  public static Charset getDefaultCharset(@NotNull Project project) {
    if (HGENCODING != null && HGENCODING.length() > 0 && Charset.isSupported(HGENCODING)) {
      return Charset.forName(HGENCODING);
    }
    else {
      Charset defaultCharset = EncodingProjectManager.getInstance(project).getDefaultCharset();
      if (defaultCharset != null) {
        return defaultCharset;
      }
    }
    return Charset.defaultCharset();
  }
}
