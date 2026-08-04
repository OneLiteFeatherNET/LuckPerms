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

package me.lucko.luckperms.minestom.extension;

import me.lucko.luckperms.minestom.app.DependencyMode;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomHandle;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomOptions;
import me.lucko.luckperms.minestom.loader.MinestomLoader;
import net.minestom.server.extensions.Extension;
import net.onelitefeather.minestom.extensions.processor.ExtensionInfo;

/**
 * LuckPerms as a Minestom extension, loaded by
 * {@code net.onelitefeather:minestom-extensions}.
 *
 * <p>This is the third packaging route over the same options/handle API. It
 * carries the JarInJar loader, so it behaves exactly like
 * {@code MinestomLoader.create(options)} in a host's {@code main()} - the only
 * difference is <i>who</i> supplies the options: here they come from the
 * extension container rather than from host code.</p>
 *
 * <p><b>No {@code extension.json} is written by hand.</b> The
 * {@code minestom-extensions-processor} generates it from the
 * {@link ExtensionInfo} annotation below, and the entrypoint it writes is always
 * the annotated class itself. The bug that blocked this route before - a
 * descriptor pointing at a class that had since been renamed - cannot happen
 * any more.</p>
 *
 * <h2>Class loading</h2>
 * <p>{@code ExtensionClassLoader} is a {@code URLClassLoader} whose parent is
 * {@code MinecraftServer.class.getClassLoader()}, and it delegates parent-first.
 * The {@code JarInJarClassLoader} this extension creates in turn has the
 * {@code ExtensionClassLoader} as its parent and also delegates parent-first, so
 * the chain is jarinjar -&gt; extension -&gt; host.</p>
 *
 * <p>The practical consequence for {@code net.luckperms:api}: this jar carries a
 * copy of it, but if the <b>host</b> declares {@code net.luckperms:api} on its
 * own class path, that copy wins for every one of the three loaders. Host and
 * extension then share the same {@code LuckPermsProvider} class object and
 * {@code LuckPermsProvider.get()} works on the host side. If the host does not
 * declare it, LuckPerms runs on the extension's own copy - fully functional
 * in-game, but invisible to host code. That is the intended degradation, not a
 * failure.</p>
 */
@ExtensionInfo(
        name = "LuckPerms",
        authors = {"Luck", "OneLiteFeatherNET"}
)
public final class LuckPermsExtension extends Extension {

    /**
     * The running instance, or null before {@link #preInitialize()} and after
     * {@link #terminate()}.
     */
    private LuckPermsMinestomHandle handle;

    /**
     * No explicit constructor on purpose: {@code ExtensionManager} instantiates
     * the entrypoint reflectively and reports a failure here as a generic
     * {@code InvocationTargetException}, so nothing that can fail belongs in it.
     */

    /**
     * Runs from inside {@code ExtensionBootstrap.init()}, i.e. directly after
     * {@code MinecraftServer.init()} and long before the port is bound.
     *
     * <p>Maps onto LuckPerms' own load phase: dependencies are resolved and the
     * configuration is read. This is the same split every upstream platform
     * uses (Bukkit {@code onLoad} / {@code onEnable}).</p>
     */
    @Override
    public void preInitialize() {
        this.handle = MinestomLoader.create(LuckPermsMinestomOptions.builder()
                // extensions/LuckPerms - the extension container already owns a
                // per-extension directory, so the old `data/` hardcode next to
                // the host process simply does not apply on this route.
                .dataDirectory(getDataDirectory())
                // ComponentLogger extends org.slf4j.Logger; named after the
                // extension, so LuckPerms' output is attributable.
                .logger(getLogger())
                // Child of the global handler, created and removed by the
                // extension container itself (ExtensionClassLoader#terminate).
                .eventNode(getEventNode())
                // The extension carries the JarInJar loader, so a real
                // ClassPathAppender exists and the upstream download path works.
                // JAR_IN_JAR would need `luckperms/deps/*.jarinjar` inside this
                // artifact, which this build does not produce.
                .dependencyMode(DependencyMode.DOWNLOAD)
                // ExtensionBootstrap registers `extensions::shutdown` as a
                // Minestom shutdown task itself, which calls terminate() below.
                // A second, JVM-level hook would race with it.
                .registerShutdownHook(false)
                .build());

        this.handle.load();
    }

    /**
     * Runs from {@code ExtensionBootstrap.start(...)}, immediately before the
     * server binds its port - so storage, commands and listeners are up before
     * the first packet can arrive.
     *
     * <p>Extensions that declare {@code dependencies = {"LuckPerms"}} are sorted
     * after this one and initialised in that order, so their own
     * {@code initialize()} already sees a running LuckPerms.</p>
     *
     * <p>This blocks: it connects to storage and, on first start, downloads the
     * runtime dependencies.</p>
     */
    @Override
    public void initialize() {
        if (this.handle == null) {
            throw new IllegalStateException(
                    "LuckPerms did not get through its load phase, so it cannot be enabled. " +
                    "See the earlier error from preInitialize().");
        }
        this.handle.enable();
    }

    /**
     * Shuts LuckPerms down: commands unregistered, event node detached, storage
     * and scheduler closed, API provider deregistered, nested class loader
     * closed.
     *
     * <p>{@code close()} is idempotent and never throws for a handle that was
     * never enabled, so no state tracking is needed beyond the null check.</p>
     */
    @Override
    public void terminate() {
        LuckPermsMinestomHandle handle = this.handle;
        this.handle = null;
        if (handle != null) {
            handle.close();
        }
    }
}
