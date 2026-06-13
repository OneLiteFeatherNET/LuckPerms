/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.locale;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * A {@link ResourceBundle.Control} that reads {@code .properties} files as UTF-8.
 *
 * <p>This used to be provided by adventure as
 * {@code net.kyori.adventure.util.UTF8ResourceBundleControl}, but that class was
 * removed in adventure 5. A local copy keeps translation loading working.</p>
 */
public final class UTF8ResourceBundleControl extends ResourceBundle.Control {
    private static final UTF8ResourceBundleControl INSTANCE = new UTF8ResourceBundleControl();

    public static UTF8ResourceBundleControl get() {
        return INSTANCE;
    }

    @Override
    public ResourceBundle newBundle(final String baseName, final Locale locale, final String format, final ClassLoader loader, final boolean reload) throws IllegalAccessException, InstantiationException, IOException {
        if (!"java.properties".equals(format)) {
            return super.newBundle(baseName, locale, format, loader, reload);
        }

        final String bundleName = this.toBundleName(baseName, locale);
        final String resourceName = this.toResourceName(bundleName, "properties");

        InputStream is = null;
        if (reload) {
            final URL url = loader.getResource(resourceName);
            if (url != null) {
                final URLConnection connection = url.openConnection();
                if (connection != null) {
                    connection.setUseCaches(false);
                    is = connection.getInputStream();
                }
            }
        } else {
            is = loader.getResourceAsStream(resourceName);
        }

        if (is == null) {
            return null;
        }

        try (final Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return new PropertyResourceBundle(reader);
        }
    }
}
