/*
 * #%L
 * JBossOSGi VFS
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.jboss.test.osgi.vfs.issues;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.TreeSet;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The file pattern of {@code VirtualFile.findEntries} (Bundle.findEntries of bundles with a VirtualFile root) is used
 * as a regular expression: '.' matches any character and '$', '(', '+', '[' are regex operators.
 *
 * The vfs30 part of the framework issue resolver/03 (FindEntriesFilePatternIssueTestCase in the framework itest).
 * Bundle.findEntries (OSGi Core R8, 10.1.5.13): "Substring matching is supported, as specified in the Filter
 * specification, using the wildcard character ("*")." In the Filter syntax (3.2.7) only '*' is a wildcard and '\'
 * escapes the next character.
 *
 * Code location: {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VFSFindEntriesEnumeration.java} (convertToPattern),
 * which only translated '*'.
 */
public class FindEntriesFilePatternIssueTestCase {

    private VirtualFile root;

    @Before
    public void setUp() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "file-pattern.jar");
        for (String name : new String[] { "Outer$Inner.class", "a(1).txt", "a+b.txt", "a.txt", "abtxt", "star*.txt" })
            archive.addAsResource(new StringAsset("content"), "p/" + name);
        InputStream input = archive.as(ZipExporter.class).exportAsInputStream();
        root = AbstractVFS.toVirtualFile("file-pattern-" + System.nanoTime(), input);
    }

    @After
    public void tearDown() {
        VFSUtils.safeClose(root);
    }

    @Test
    public void testLiteralCharacters() throws Exception {
        assertEquals("findEntries(\"p\", \"Outer$Inner.class\")", "[Outer$Inner.class]", findEntries("Outer$Inner.class"));
        assertEquals("findEntries(\"p\", \"a(1).txt\")", "[a(1).txt]", findEntries("a(1).txt"));
        assertEquals("findEntries(\"p\", \"a+b.txt\")", "[a+b.txt]", findEntries("a+b.txt"));
        assertEquals("findEntries(\"p\", \"*$*.class\")", "[Outer$Inner.class]", findEntries("*$*.class"));
    }

    @Test
    public void testDotIsNoWildcard() throws Exception {
        assertEquals("findEntries(\"p\", \"*.txt\")", "[a(1).txt, a+b.txt, a.txt, star*.txt]", findEntries("*.txt"));
        assertEquals("findEntries(\"p\", \"a.txt\")", "[a.txt]", findEntries("a.txt"));
    }

    @Test
    public void testBracket() throws Exception {
        String result;
        try {
            result = findEntries("a[");
        } catch (RuntimeException ex) {
            result = "must not throw: " + ex;
        }
        assertEquals("findEntries(\"p\", \"a[\") matches no entry", "[]", result);
    }

    @Test
    public void testEscapedWildcard() throws Exception {
        assertEquals("findEntries(\"p\", \"star\\\\*.txt\")", "[star*.txt]", findEntries("star\\*.txt"));
        assertEquals("findEntries(\"p\", \"a\\\\(1\\\\).txt\")", "[a(1).txt]", findEntries("a\\(1\\).txt"));
    }

    private String findEntries(String pattern) throws Exception {
        TreeSet<String> result = new TreeSet<String>();
        Enumeration<URL> entries = root.findEntries("p", pattern, false);
        while (entries != null && entries.hasMoreElements())
            result.add(AbstractVFS.toVirtualFile(entries.nextElement()).getName());
        return result.toString();
    }
}
