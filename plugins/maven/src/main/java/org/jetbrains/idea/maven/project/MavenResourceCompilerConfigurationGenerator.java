package org.jetbrains.idea.maven.project;

import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.CompilerConfigurationImpl;
import com.intellij.compiler.server.BuildManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Document;
import org.jdom.Element;
import org.jetbrains.idea.maven.dom.references.MavenFilteredPropertyPsiReferenceProvider;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.model.MavenResource;
import org.jetbrains.idea.maven.utils.MavenJDOMUtil;
import org.jetbrains.idea.maven.utils.MavenUtil;
import org.jetbrains.jps.maven.model.impl.MavenIdBean;
import org.jetbrains.jps.maven.model.impl.MavenModuleResourceConfiguration;
import org.jetbrains.jps.maven.model.impl.MavenProjectConfiguration;
import org.jetbrains.jps.maven.model.impl.ResourceRootConfiguration;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Sergey Evdokimov
 */
public class MavenResourceCompilerConfigurationGenerator {

  private static final Pattern SIMPLE_NEGATIVE_PATTERN = Pattern.compile("!\\?(\\*\\.\\w+)");

  private final Project myProject;

  private final MavenProjectsManager myMavenProjectsManager;

  private final MavenProjectsTree myProjectsTree;

  public MavenResourceCompilerConfigurationGenerator(Project project, MavenProjectsTree projectsTree) {
    myProject = project;
    myMavenProjectsManager = MavenProjectsManager.getInstance(project);
    myProjectsTree = projectsTree;
  }

  public void generateBuildConfiguration(boolean force) {
    if (!myMavenProjectsManager.isMavenizedProject()) {
      return;
    }

    final BuildManager buildManager = BuildManager.getInstance();
    final File projectSystemDir = buildManager.getProjectSystemDirectory(myProject);
    if (projectSystemDir == null) {
      return;
    }

    final File mavenConfigFile = new File(projectSystemDir, MavenProjectConfiguration.CONFIGURATION_FILE_RELATIVE_PATH);

    ProjectRootManager projectRootManager = ProjectRootManager.getInstance(myProject);

    ProjectFileIndex fileIndex = projectRootManager.getFileIndex();

    final int crc = myProjectsTree.getFilterConfigCrc(fileIndex) + (int)projectRootManager.getModificationCount();

    final File crcFile = new File(mavenConfigFile.getParent(), "configuration.crc");

    if (!force) {
      try {
        DataInputStream crcInput = new DataInputStream(new FileInputStream(crcFile));
        try {
          if (crcInput.readInt() == crc) return; // Project had not change since last config generation.
        }
        finally {
          crcInput.close();
        }
      }
      catch (IOException ignored) {
        // // Config file is not generated.
      }
    }

    MavenProjectConfiguration projectConfig = new MavenProjectConfiguration();

    for (MavenProject mavenProject : myMavenProjectsManager.getProjects()) {
      VirtualFile pomXml = mavenProject.getFile();

      Module module = fileIndex.getModuleForFile(pomXml);
      if (module == null) continue;

      if (mavenProject.getDirectoryFile() != fileIndex.getContentRootForFile(pomXml)) continue;

      MavenModuleResourceConfiguration resourceConfig = new MavenModuleResourceConfiguration();

      MavenId projectId = mavenProject.getMavenId();
      resourceConfig.id = new MavenIdBean(projectId.getGroupId(), projectId.getArtifactId(), projectId.getVersion());

      MavenId parentId = mavenProject.getParentId();
      if (parentId != null) {
        resourceConfig.parentId = new MavenIdBean(parentId.getGroupId(), parentId.getArtifactId(), parentId.getVersion());
      }
      resourceConfig.directory = FileUtil.toSystemIndependentName(mavenProject.getDirectory());
      resourceConfig.delimitersPattern = MavenFilteredPropertyPsiReferenceProvider.getDelimitersPattern(mavenProject).pattern();
      for (Map.Entry<String, String> entry : mavenProject.getModelMap().entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (value != null) {
          resourceConfig.modelMap.put(key, value);
        }
      }
      addResources(resourceConfig.resources, mavenProject.getResources());
      addResources(resourceConfig.testResources, mavenProject.getTestResources());
      resourceConfig.filteringExclusions.addAll(MavenProjectsTree.getFilterExclusions(mavenProject));

      final Properties properties = getFilteringProperties(mavenProject);
      for (Map.Entry<Object, Object> propEntry : properties.entrySet()) {
        resourceConfig.properties.put((String)propEntry.getKey(), (String)propEntry.getValue());
      }

      Element pluginConfiguration = mavenProject.getPluginConfiguration("org.apache.maven.plugins", "maven-resources-plugin");
      resourceConfig.escapeString = MavenJDOMUtil.findChildValueByPath(pluginConfiguration, "escapeString", "\\");
      String escapeWindowsPaths = MavenJDOMUtil.findChildValueByPath(pluginConfiguration, "escapeWindowsPaths");
      if (escapeWindowsPaths != null) {
        resourceConfig.escapeWindowsPaths = Boolean.parseBoolean(escapeWindowsPaths);
      }

      projectConfig.moduleConfigurations.put(module.getName(), resourceConfig);
    }

    addNonMavenResources(projectConfig);

    final Document document = new Document(new Element("maven-project-configuration"));
    XmlSerializer.serializeInto(projectConfig, document.getRootElement());
    buildManager.runCommand(new Runnable() {
      @Override
      public void run() {
        buildManager.clearState(myProject);
        FileUtil.createIfDoesntExist(mavenConfigFile);
        try {
          JDOMUtil.writeDocument(document, mavenConfigFile, "\n");

          DataOutputStream crcOutput = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(crcFile)));
          try {
            crcOutput.writeInt(crc);
          }
          finally {
            crcOutput.close();
          }
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  private Properties getFilteringProperties(MavenProject mavenProject) {
    final Properties properties = new Properties();

    for (String each : mavenProject.getFilters()) {
      try {
        FileInputStream in = new FileInputStream(each);
        try {
          properties.load(in);
        }
        finally {
          in.close();
        }
      }
      catch (IOException ignored) {
      }
    }

    properties.putAll(mavenProject.getProperties());

    properties.put("settings.localRepository", mavenProject.getLocalRepository().getAbsolutePath());

    String jreDir = MavenUtil.getModuleJreHome(myMavenProjectsManager, mavenProject);
    if (jreDir != null) {
      properties.put("java.home", jreDir);
    }

    String javaVersion = MavenUtil.getModuleJavaVersion(myMavenProjectsManager, mavenProject);
    if (javaVersion != null) {
      properties.put("java.version", javaVersion);
    }

    return properties;
  }

  private static void addResources(final List<ResourceRootConfiguration> container, Collection<MavenResource> resources) {
    for (MavenResource resource : resources) {
      final String dir = resource.getDirectory();
      if (dir == null) {
        continue;
      }

      final ResourceRootConfiguration props = new ResourceRootConfiguration();
      props.directory = FileUtil.toSystemIndependentName(dir);

      final String target = resource.getTargetPath();
      props.targetPath = target != null ? FileUtil.toSystemIndependentName(target) : null;

      props.isFiltered = resource.isFiltered();
      props.includes.clear();
      for (String include : resource.getIncludes()) {
        props.includes.add(include.trim());
      }
      props.excludes.clear();
      for (String exclude : resource.getExcludes()) {
        props.excludes.add(exclude.trim());
      }
      container.add(props);
    }
  }

  private void addNonMavenResources(MavenProjectConfiguration projectCfg) {
    Set<VirtualFile> processedRoots = new HashSet<VirtualFile>();

    for (MavenProject project : myMavenProjectsManager.getProjects()) {
      for (String dir : ContainerUtil.concat(project.getSources(), project.getTestSources())) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(dir);
        if (file != null) {
          processedRoots.add(file);
        }
      }

      for (MavenResource resource : ContainerUtil.concat(project.getResources(), project.getTestResources())) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(resource.getDirectory());
        if (file != null) {
          processedRoots.add(file);
        }
      }
    }

    CompilerConfiguration compilerConfiguration = CompilerConfiguration.getInstance(myProject);

    for (Module module : ModuleManager.getInstance(myProject).getModules()) {
      if (!myMavenProjectsManager.isMavenizedModule(module)) continue;

      for (ContentEntry contentEntry : ModuleRootManager.getInstance(module).getContentEntries()) {
        for (SourceFolder folder : contentEntry.getSourceFolders()) {
          VirtualFile file = folder.getFile();
          if (file == null) continue;

          if (!compilerConfiguration.isExcludedFromCompilation(file) && !isUnderRoots(processedRoots, file)) {
            MavenModuleResourceConfiguration configuration = projectCfg.moduleConfigurations.get(module.getName());
            if (configuration == null) continue;

            List<ResourceRootConfiguration> resourcesList = folder.isTestSource() ? configuration.testResources : configuration.resources;

            final ResourceRootConfiguration cfg = new ResourceRootConfiguration();
            cfg.directory = FileUtil.toSystemIndependentName(FileUtil.toSystemIndependentName(file.getPath()));

            CompilerModuleExtension compilerModuleExtension = CompilerModuleExtension.getInstance(module);
            if (compilerModuleExtension == null) continue;


            String compilerOutputUrl = folder.isTestSource()
                                       ? compilerModuleExtension.getCompilerOutputUrlForTests()
                                       : compilerModuleExtension.getCompilerOutputUrl();

            cfg.targetPath = VfsUtil.urlToPath(compilerOutputUrl);

            convertIdeaExcludesToMavenExcludes(cfg, (CompilerConfigurationImpl)compilerConfiguration);

            resourcesList.add(cfg);
          }
        }
      }
    }
  }

  private static void convertIdeaExcludesToMavenExcludes(ResourceRootConfiguration cfg, CompilerConfigurationImpl compilerConfiguration) {
    for (String pattern : compilerConfiguration.getResourceFilePatterns()) {
      Matcher matcher = SIMPLE_NEGATIVE_PATTERN.matcher(pattern);
      if (matcher.matches()) {
        cfg.excludes.add("**/" + matcher.group(1));
      }
    }
  }

  private static boolean isUnderRoots(Set<VirtualFile> roots, VirtualFile file) {
    for (VirtualFile f = file; f != null; f = f.getParent()) {
      if (roots.contains(file)) {
        return true;
      }
    }

    return false;
  }

}
