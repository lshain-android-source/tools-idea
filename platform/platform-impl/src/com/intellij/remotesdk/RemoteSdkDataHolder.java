package com.intellij.remotesdk;

import com.intellij.openapi.util.text.StringUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * @author traff
 */
public class RemoteSdkDataHolder extends RemoteCredentialsHolder implements RemoteSdkData {

  public static final String SSH_PREFIX = "ssh://";
  private static final String HOST = "HOST";
  private static final String PORT = "PORT";
  private static final String ANONYMOUS = "ANONYMOUS";
  private static final String USERNAME = "USERNAME";
  private static final String PASSWORD = "PASSWORD";
  private static final String USE_KEY_PAIR = "USE_KEY_PAIR";
  private static final String PRIVATE_KEY_FILE = "PRIVATE_KEY_FILE";
  private static final String KNOWN_HOSTS_FILE = "MY_KNOWN_HOSTS_FILE";
  private static final String PASSPHRASE = "PASSPHRASE";
  private static final String INTERPRETER_PATH = "INTERPRETER_PATH";
  private static final String HELPERS_PATH = "HELPERS_PATH";
  private static final String REMOTE_ROOTS = "REMOTE_ROOTS";
  private static final String REMOTE_PATH = "REMOTE_PATH";
  private static final String INITIALIZED = "INITIALIZED";


  private String myInterpreterPath;
  private String myHelpersPath;

  private final String myHelpersDefaultDirName;

  private boolean myHelpersVersionChecked = false;

  private List<String> myRemoteRoots = new ArrayList<String>();

  private boolean myInitialized;

  public RemoteSdkDataHolder(@NotNull final String defaultDirName) {
    myHelpersDefaultDirName = defaultDirName;
  }

  @Override
  public String getInterpreterPath() {
    return myInterpreterPath;
  }

  @Override
  public void setInterpreterPath(String interpreterPath) {
    myInterpreterPath = interpreterPath;
  }


  @Override
  public String getFullInterpreterPath() {
    return SSH_PREFIX + getUserName() + "@" + getHost() + ":" + getPort() + myInterpreterPath;
  }

  @Override
  public String getHelpersPath() {
    return myHelpersPath;
  }

  @Override
  public void setHelpersPath(String helpersPath) {
    myHelpersPath = helpersPath;
  }

  public String getDefaultHelpersName() {
    return myHelpersDefaultDirName;
  }

  @Override
  public void addRemoteRoot(String remoteRoot) {
    myRemoteRoots.add(remoteRoot);
  }

  @Override
  public void clearRemoteRoots() {
    myRemoteRoots.clear();
  }

  @Override
  public List<String> getRemoteRoots() {
    return myRemoteRoots;
  }

  @Override
  public void setRemoteRoots(List<String> remoteRoots) {
    myRemoteRoots = remoteRoots;
  }

  @Override
  public boolean isHelpersVersionChecked() {
    return myHelpersVersionChecked;
  }

  @Override
  public void setHelpersVersionChecked(boolean helpersVersionChecked) {
    myHelpersVersionChecked = helpersVersionChecked;
  }

  @Override
  public boolean isInitialized() {
    return myInitialized;
  }

  @Override
  public void setInitialized(boolean initialized) {
    myInitialized = initialized;
  }

  public static boolean isRemoteSdk(@Nullable String path) {
    if (path != null) {
      return path.startsWith(SSH_PREFIX);
    }
    else {
      return false;
    }
  }

  public void loadRemoteSdkData(Element element) {
    setHost(element.getAttributeValue(HOST));
    setPort(StringUtil.parseInt(element.getAttributeValue(PORT), 22));
    setAnonymous(StringUtil.parseBoolean(element.getAttributeValue(ANONYMOUS), false));
    setSerializedUserName(element.getAttributeValue(USERNAME));
    setSerializedPassword(element.getAttributeValue(PASSWORD));
    setPrivateKeyFile(StringUtil.nullize(element.getAttributeValue(PRIVATE_KEY_FILE)));
    setKnownHostsFile(StringUtil.nullize(element.getAttributeValue(KNOWN_HOSTS_FILE)));
    setSerializedPassphrase(element.getAttributeValue(PASSPHRASE));
    setUseKeyPair(StringUtil.parseBoolean(element.getAttributeValue(USE_KEY_PAIR), false));

    setInterpreterPath(StringUtil.nullize(element.getAttributeValue(INTERPRETER_PATH)));
    setHelpersPath(StringUtil.nullize(element.getAttributeValue(HELPERS_PATH)));

    setRemoteRoots(loadStringsList(element, REMOTE_ROOTS, REMOTE_PATH));

    setInitialized(StringUtil.parseBoolean(element.getAttributeValue(INITIALIZED), true));
  }

  protected static List<String> loadStringsList(Element element, String rootName, String attrName) {
    final List<String> paths = new LinkedList<String>();
    if (element != null) {
      @NotNull final List list = element.getChildren(rootName);
      for (Object o : list) {
        paths.add(((Element)o).getAttribute(attrName).getValue());
      }
    }
    return paths;
  }

  public void saveRemoteSdkData(Element rootElement) {
    rootElement.setAttribute(HOST, StringUtil.notNullize(getHost()));
    rootElement.setAttribute(PORT, Integer.toString(getPort()));
    rootElement.setAttribute(ANONYMOUS, Boolean.toString(isAnonymous()));
    rootElement.setAttribute(USERNAME, getSerializedUserName());
    rootElement.setAttribute(PASSWORD, getSerializedPassword());
    rootElement.setAttribute(PRIVATE_KEY_FILE, StringUtil.notNullize(getPrivateKeyFile()));
    rootElement.setAttribute(KNOWN_HOSTS_FILE, StringUtil.notNullize(getKnownHostsFile()));
    rootElement.setAttribute(PASSPHRASE, getSerializedPassphrase());
    rootElement.setAttribute(USE_KEY_PAIR, Boolean.toString(isUseKeyPair()));

    rootElement.setAttribute(INTERPRETER_PATH, StringUtil.notNullize(getInterpreterPath()));
    rootElement.setAttribute(HELPERS_PATH, StringUtil.notNullize(getHelpersPath()));

    rootElement.setAttribute(INITIALIZED, Boolean.toString(isInitialized()));

    for (String remoteRoot : getRemoteRoots()) {
      final Element child = new Element(REMOTE_ROOTS);
      child.setAttribute(REMOTE_PATH, remoteRoot);
      rootElement.addContent(child);
    }
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    RemoteSdkDataHolder holder = (RemoteSdkDataHolder)o;

    if (isAnonymous() != holder.isAnonymous()) return false;
    if (myHelpersVersionChecked != holder.myHelpersVersionChecked) return false;
    if (getPort() != holder.getPort()) return false;
    if (isStorePassphrase() != holder.isStorePassphrase()) return false;
    if (isStorePassword() != holder.isStorePassword()) return false;
    if (isUseKeyPair() != holder.isUseKeyPair()) return false;
    if (getHost() != null ? !getHost().equals(holder.getHost()) : holder.getHost() != null) return false;
    if (myInterpreterPath != null ? !myInterpreterPath.equals(holder.myInterpreterPath) : holder.myInterpreterPath != null) return false;
    if (getKnownHostsFile() != null ? !getKnownHostsFile().equals(holder.getKnownHostsFile()) : holder.getKnownHostsFile() != null) {
      return false;
    }
    if (getPassphrase() != null ? !getPassphrase().equals(holder.getPassphrase()) : holder.getPassphrase() != null) return false;
    if (getPassword() != null ? !getPassword().equals(holder.getPassword()) : holder.getPassword() != null) return false;
    if (getPrivateKeyFile() != null ? !getPrivateKeyFile().equals(holder.getPrivateKeyFile()) : holder.getPrivateKeyFile() != null) {
      return false;
    }
    if (myHelpersPath != null
        ? !myHelpersPath.equals(holder.myHelpersPath)
        : holder.myHelpersPath != null) {
      return false;
    }
    if (myRemoteRoots != null ? !myRemoteRoots.equals(holder.myRemoteRoots) : holder.myRemoteRoots != null) return false;
    if (getUserName() != null ? !getUserName().equals(holder.getUserName()) : holder.getUserName() != null) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = getHost() != null ? getHost().hashCode() : 0;
    result = 31 * result + getPort();
    result = 31 * result + (isAnonymous() ? 1 : 0);
    result = 31 * result + (getUserName() != null ? getUserName().hashCode() : 0);
    result = 31 * result + (getPassword() != null ? getPassword().hashCode() : 0);
    result = 31 * result + (isUseKeyPair() ? 1 : 0);
    result = 31 * result + (getPrivateKeyFile() != null ? getPrivateKeyFile().hashCode() : 0);
    result = 31 * result + (getKnownHostsFile() != null ? getKnownHostsFile().hashCode() : 0);
    result = 31 * result + (getPassphrase() != null ? getPassphrase().hashCode() : 0);
    result = 31 * result + (isStorePassword() ? 1 : 0);
    result = 31 * result + (isStorePassphrase() ? 1 : 0);
    result = 31 * result + (myInterpreterPath != null ? myInterpreterPath.hashCode() : 0);
    result = 31 * result + (myHelpersPath != null ? myHelpersPath.hashCode() : 0);
    result = 31 * result + (myHelpersVersionChecked ? 1 : 0);
    result = 31 * result + (myRemoteRoots != null ? myRemoteRoots.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("RemoteSdkDataHolder");
    sb.append("{getHost()='").append(getHost()).append('\'');
    sb.append(", getPort()=").append(getPort());
    sb.append(", isAnonymous()=").append(isAnonymous());
    sb.append(", getUserName()='").append(getUserName()).append('\'');
    sb.append(", myInterpreterPath='").append(myInterpreterPath).append('\'');
    sb.append(", myHelpersPath='").append(myHelpersPath).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
