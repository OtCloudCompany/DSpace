/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.general;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;

public class CleanMetadataTest {

    private CleanMetadata task;

    @Before
    public void setUp() {
        task = new CleanMetadata();
    }

    private String invoke(String name, String input) throws Exception {
        Method method = CleanMetadata.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        return (String) method.invoke(task, input);
    }

    @Test
    public void normalizeQuotes_shouldReplaceAllUnicodeQuoteVariants() throws Exception {
        String input = "\u201CFix\u201D \u201Atest\u201B \u2033prime\u2036";
        String actual = invoke("normalizeQuotes", input);

        assertEquals("\"Fix\" 'test' \"prime\"", actual);
    }

    @Test
    public void normalizeDashes_shouldNormalizeExtendedDashCharacters() throws Exception {
        String input = "words--words\u2013words\u2014words\u2212words";
        String actual = invoke("normalizeDashes", input);

        assertEquals("words—words—words—words—words", actual);
    }

    @Test
    public void normalizeWhitespace_shouldCollapseUnicodeSpaces() throws Exception {
        String input = "\u00A0  text\u2009with\u202Fvarious\u3000spaces ";
        String actual = invoke("normalizeWhitespace", input);

        assertEquals("text with various spaces", actual);
    }
}
