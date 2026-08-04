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

package me.lucko.luckperms.minestom.app;

/**
 * Controls where LuckPerms obtains its runtime dependencies from.
 *
 * <p>LuckPerms does not bundle its storage drivers, configuration library and
 * various other libraries into its own jar. On every upstream platform they are
 * fetched at runtime and appended to a dedicated class loader. Which of the
 * strategies below is usable depends entirely on <i>how</i> LuckPerms was
 * packaged, which is why this is an option and not a constant.</p>
 *
 * @see LuckPermsMinestomOptions.Builder#dependencyMode(DependencyMode)
 */
public enum DependencyMode {

    /**
     * Download dependencies from the LuckPerms Maven mirror (falling back to
     * Maven Central) into {@code <dataDirectory>/libs} and append them to the
     * class path at runtime.
     *
     * <p>This is the default and is literally the upstream behaviour - ten of
     * the twelve upstream platforms do exactly this.</p>
     *
     * <p><b>Requires a usable {@code ClassPathAppender}</b>, which in practice
     * means LuckPerms has to be loaded through a {@code JarInJarClassLoader}.
     * When LuckPerms is shaded flat into a consumer's fat jar there is no class
     * loader that accepts additional URLs, so downloaded jars would be silently
     * discarded. That combination is rejected at construction time rather than
     * failing later with an unexplained {@code NoClassDefFoundError}.</p>
     */
    DOWNLOAD,

    /**
     * Resolve dependencies from {@code luckperms/deps/} inside the jar instead
     * of downloading them.
     *
     * <p>Like {@link #DOWNLOAD} this still appends to the class path, so it also
     * requires a working {@code ClassPathAppender}. It merely removes the
     * network round trip.</p>
     */
    JAR_IN_JAR,

    /**
     * Assume every dependency is already on the class path and load nothing.
     *
     * <p>This is the mode the flat library packaging has to use: all libraries
     * are shaded into the artifact at build time, so there is nothing left to
     * resolve. It is a deliberate, documented deviation from the LuckPerms
     * standard, forced by the packaging - not an optimisation to pick.</p>
     *
     * <p>It is also the mode to use in tests, where the whole class path is
     * provided by the build tool.</p>
     */
    PRELOADED
}
