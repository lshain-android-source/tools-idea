package com.intellij.execution.configurations;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Splits input String to tokens being aware of quoted tokens ("foo bar") and escaped spaces (foo\ bar),
 * usually used for splitting command line to separate arguments that may contain space symbols.
 * Escaped symbols are not handled so there's no way to get token that itself contains quotation mark.
 */
public class CommandLineTokenizer extends StringTokenizer {

    private static String DEFAULT_DELIMITERS = " \t\n\r\f";
    // keep source level 1.4
    private List myTokens = new ArrayList();
    private int myCurrentToken = 0;
    private boolean myHandleEscapedWhitespaces = false;

    public CommandLineTokenizer(String str) {
      this(str, false);
    }

    public CommandLineTokenizer(String str, boolean handleEscapedWhitespaces) {
        super(str, DEFAULT_DELIMITERS, true);
        myHandleEscapedWhitespaces = handleEscapedWhitespaces;
        parseTokens();
    }

    /**
     * @deprecated Do not pass custom delimiters to the CommandLineTokenizer as it may break its logic
     */
    @Deprecated()
    public CommandLineTokenizer(String str, String delim) {
        super(str, delim, true);
        parseTokens();
    }

    public boolean hasMoreTokens() {
        return myCurrentToken < myTokens.size();
    }

    public String nextToken() {
        return (String) myTokens.get(myCurrentToken++);
    }

    public String peekNextToken() {
        return (String) myTokens.get(myCurrentToken);
    }

    public int countTokens() {
        return myTokens.size() - myCurrentToken;
    }


    public String nextToken(String delim) {
        throw new UnsupportedOperationException();
    }

    private void parseTokens() {
        String token;
        while ((token = nextTokenInternal()) != null) {
            myTokens.add(token);
        }
    }

    private String nextTokenInternal() {
        String nextToken;
        do {
            if (super.hasMoreTokens()) {
                nextToken = super.nextToken();
            } else {
                nextToken = null;
            }
        } while (nextToken != null && nextToken.length() == 1 && DEFAULT_DELIMITERS.indexOf(nextToken.charAt(0)) >= 0);

        if (nextToken == null) {
            return null;
        }

        int i;
        int quotationMarks = 0;
        final StringBuffer buffer = new StringBuffer();

        do {
            while ((i = nextToken.indexOf('"')) >= 0) {
                quotationMarks++;
                buffer.append(nextToken.substring(0, i));
                nextToken = nextToken.substring(i + 1);
            }

            boolean isEscapedWhitespace = false;
            if (myHandleEscapedWhitespaces && quotationMarks == 0 && nextToken.endsWith("\\") && super.hasMoreTokens()) {
              isEscapedWhitespace = true;
              buffer.append(nextToken.substring(0, nextToken.length() - 1));
              buffer.append(super.nextToken());
            }
            else {
              buffer.append(nextToken);
            }

            if ((isEscapedWhitespace || quotationMarks % 2 == 1) && super.hasMoreTokens()) {
                nextToken = super.nextToken();
            } else {
                nextToken = null;
            }
        } while (nextToken != null);

        return buffer.toString();
    }
}
