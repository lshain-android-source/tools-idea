/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package org.jetbrains.plugins.github.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.HyperlinkAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.github.*;
import org.jetbrains.plugins.github.api.GithubUserDetailed;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;

/**
 * @author oleg
 * @date 10/20/10
 */
public class GithubSettingsPanel {
  private static final String DEFAULT_PASSWORD_TEXT = "************";
  private final static String AUTH_PASSWORD = "Password";
  private final static String AUTH_TOKEN = "Token";

  private static final Logger LOG = GithubUtil.LOG;

  private final GithubSettings mySettings;

  private JTextField myLoginTextField;
  private JPasswordField myPasswordField;
  private JTextPane mySignupTextField;
  private JPanel myPane;
  private JButton myTestButton;
  private JTextField myHostTextField;
  private JComboBox myAuthTypeComboBox;

  private boolean myCredentialsModified;

  public GithubSettingsPanel(@NotNull final GithubSettings settings) {
    mySettings = settings;
    mySignupTextField.addHyperlinkListener(new HyperlinkAdapter() {
      @Override
      protected void hyperlinkActivated(final HyperlinkEvent e) {
        BrowserUtil.browse(e.getURL());
      }
    });
    mySignupTextField.setText(
      "<html>Do not have an account at github.com? <a href=\"https://github.com\">" + "Sign up" + "</a></html>");
    mySignupTextField.setBackground(myPane.getBackground());
    mySignupTextField.setCursor(new Cursor(Cursor.HAND_CURSOR));
    myAuthTypeComboBox.addItem(AUTH_PASSWORD);
    myAuthTypeComboBox.addItem(AUTH_TOKEN);

    reset();

    myTestButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          GithubUserDetailed user = GithubUtil.checkAuthData(getAuthData());
          if (!getLogin().equalsIgnoreCase(user.getLogin())) {
            setLogin(user.getLogin());
            Messages.showInfoMessage(myPane, "Login doesn't match credentials. Fixed", "Success");
            return;
          }
          Messages.showInfoMessage(myPane, "Connection successful", "Success");
        }
        catch (GithubAuthenticationException ex) {
          Messages.showErrorDialog(myPane, "Can't login using given credentials: " + ex.getMessage(), "Login Failure");
        }
        catch (IOException ex) {
          LOG.info(ex);
          Messages.showErrorDialog(myPane, "Can't login: " + GithubUtil.getErrorTextFromException(ex), "Login Failure");
        }
      }
    });

    myPasswordField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        myCredentialsModified = true;
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        myCredentialsModified = true;
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        myCredentialsModified = true;
      }
    });

    DocumentListener passwordEraser = new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        if (!myCredentialsModified) {
          setPassword("");
          myCredentialsModified = true;
        }
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        if (!myCredentialsModified) {
          setPassword("");
          myCredentialsModified = true;
        }
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        if (!myCredentialsModified) {
          setPassword("");
          myCredentialsModified = true;
        }
      }
    };

    myHostTextField.getDocument().addDocumentListener(passwordEraser);
    myLoginTextField.getDocument().addDocumentListener(passwordEraser);

    myPasswordField.addFocusListener(new FocusListener() {
      @Override
      public void focusGained(FocusEvent e) {
        if (!myCredentialsModified && !getPassword().isEmpty()) {
          setPassword("");
          myCredentialsModified = true;
        }
      }

      @Override
      public void focusLost(FocusEvent e) {
      }
    });

    myAuthTypeComboBox.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        setPassword("");
        myCredentialsModified = true;
      }
    });
  }

  public JComponent getPanel() {
    return myPane;
  }

  @NotNull
  public String getHost() {
    return myHostTextField.getText().trim();
  }

  @NotNull
  public String getLogin() {
    return myLoginTextField.getText().trim();
  }

  public void setHost(@NotNull final String host) {
    myHostTextField.setText(host);
  }

  public void setLogin(@NotNull final String login) {
    myLoginTextField.setText(login);
  }

  @NotNull
  private String getPassword() {
    return String.valueOf(myPasswordField.getPassword());
  }

  private void setPassword(@NotNull final String password) {
    // Show password as blank if password is empty
    myPasswordField.setText(StringUtil.isEmpty(password) ? null : password);
  }

  @NotNull
  public GithubAuthData.AuthType getAuthType() {
    Object selected = myAuthTypeComboBox.getSelectedItem();
    if (AUTH_PASSWORD.equals(selected)) return GithubAuthData.AuthType.BASIC;
    if (AUTH_TOKEN.equals(selected)) return GithubAuthData.AuthType.TOKEN;
    LOG.error("GithubSettingsPanel: illegal selection: basic AuthType returned", selected.toString());
    return GithubAuthData.AuthType.BASIC;
  }

  public void setAuthType(@NotNull final GithubAuthData.AuthType type) {
    switch (type) {
      case BASIC:
        myAuthTypeComboBox.setSelectedItem(AUTH_PASSWORD);
        break;
      case TOKEN:
        myAuthTypeComboBox.setSelectedItem(AUTH_TOKEN);
        break;
      case ANONYMOUS:
      default:
        myAuthTypeComboBox.setSelectedItem(AUTH_PASSWORD);
    }
  }

  @NotNull
  public GithubAuthData getAuthData() {
    if (!myCredentialsModified) {
      return mySettings.getAuthData();
    }
    Object selected = myAuthTypeComboBox.getSelectedItem();
    if (AUTH_PASSWORD.equals(selected)) return GithubAuthData.createBasicAuth(getHost(), getLogin(), getPassword());
    if (AUTH_TOKEN.equals(selected)) return GithubAuthData.createTokenAuth(getHost(), getPassword());
    LOG.error("GithubSettingsPanel: illegal selection: anonymous AuthData created", selected.toString());
    return GithubAuthData.createAnonymous(getHost());
  }

  public void reset() {
    String login = mySettings.getLogin();
    setHost(mySettings.getHost());
    setLogin(login);
    setPassword(login.isEmpty() ? "" : DEFAULT_PASSWORD_TEXT);
    setAuthType(mySettings.getAuthType());
    resetCredentialsModification();
  }

  public boolean isModified() {
    return !Comparing.equal(mySettings.getHost(), getHost()) ||
           !Comparing.equal(mySettings.getLogin(), getLogin()) ||
           myCredentialsModified;
  }

  public void resetCredentialsModification() {
    myCredentialsModified = false;
  }
}

