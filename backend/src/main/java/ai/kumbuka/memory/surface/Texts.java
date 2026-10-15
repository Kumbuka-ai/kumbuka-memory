/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import io.quarkus.qute.i18n.MessageBundles;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the code reaches the texts it addresses to a caller.
 *
 * <p>Every such text lives in a message bundle (code-conventions/
 * service-construction.md), and this is the one door to them, so that no
 * class reaches past it into a properties file or grows a text of its own.
 * A bundle is resolved when a text is asked for, not when a class is loaded:
 * the bundles exist once the application runs.
 *
 * <p>{@link #template} is the one exception to rendering: the declaration
 * publishes a refusal's pattern with its placeholders standing, which is the
 * source text and not a rendered one.
 */
public final class Texts {

    private static final Map<String, Properties> SOURCES = new ConcurrentHashMap<>();

    private Texts() {
    }

    public static RefusalMessages refusals() {
        return MessageBundles.get(RefusalMessages.class);
    }

    public static RefusalRemedies remedies() {
        return MessageBundles.get(RefusalRemedies.class);
    }

    public static ToolDescriptions tools() {
        return MessageBundles.get(ToolDescriptions.class);
    }

    public static NextStepTexts next() {
        return MessageBundles.get(NextStepTexts.class);
    }

    public static ArgumentDescriptions arguments() {
        return MessageBundles.get(ArgumentDescriptions.class);
    }

    public static ValueDescriptions values() {
        return MessageBundles.get(ValueDescriptions.class);
    }

    public static Notices notices() {
        return MessageBundles.get(Notices.class);
    }

    /**
     * A text as its bundle's file writes it, placeholders standing.
     *
     * @param bundle the bundle's name, which is its file's name
     * @param key    the method's name, which is its key
     */
    public static String template(String bundle, String key) {
        Properties source = SOURCES.computeIfAbsent(bundle, Texts::load);
        String template = source.getProperty(key);
        if (template == null) {
            throw new IllegalStateException(bundle + " has no text under " + key);
        }
        return template;
    }

    private static Properties load(String bundle) {
        String resource = "messages/" + bundle + ".properties";
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing");
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
