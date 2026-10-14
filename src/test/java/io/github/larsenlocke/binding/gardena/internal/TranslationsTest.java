/*
 * Copyright (c) 2026 Larsen-Locke and contributors
 *
 * See the NOTICE file distributed with this work for additional information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.larsenlocke.binding.gardena.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Keeps the translation files in step with each other and with the code. Nothing at build time catches a
 * missing key otherwise; the UI just shows the raw key.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class TranslationsTest {

    private static final Pattern TEXT_KEY = Pattern.compile("@text/([a-z0-9.-]+)");

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Objects
                .requireNonNull(TranslationsTest.class.getResourceAsStream("/OH-INF/i18n/" + name))) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    void germanTranslationHasTheSameKeys() throws IOException {
        assertEquals(new TreeSet<>(load("gardenalocal.properties").stringPropertyNames()),
                new TreeSet<>(load("gardenalocal_de.properties").stringPropertyNames()));
    }

    @Test
    void everyTextKeyUsedInCodeIsTranslated() throws IOException {
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = TEXT_KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        assertFalse(used.isEmpty());
        Set<String> defined = load("gardenalocal.properties").stringPropertyNames();
        for (String key : used) {
            assertTrue(defined.contains(key), "missing translation for " + key);
        }
    }
}
