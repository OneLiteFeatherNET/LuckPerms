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

package me.lucko.luckperms.minestom.loader;

import me.lucko.luckperms.common.loader.JarInJarClassLoader;
import me.lucko.luckperms.common.loader.LoaderBootstrap;
import me.lucko.luckperms.minestom.app.LuckPermsApplication;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomHandle;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomInstanceLock;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomOptions;

import java.io.IOException;
import java.util.Objects;

/**
 * Entry point for the JarInJar packaging: LuckPerms and all of its dependencies
 * live in a nested jar loaded by a {@link JarInJarClassLoader}.
 *
 * <p>This is the packaging that matches upstream LuckPerms - ten of the twelve
 * upstream platforms work exactly this way - and it is the only one where
 * {@link me.lucko.luckperms.minestom.app.DependencyMode#DOWNLOAD} can work,
 * because the nested loader is the thing dependencies get appended to.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * MinecraftServer server = MinecraftServer.init();   // must happen first
 *
 * LuckPermsMinestomHandle luckPerms = MinestomLoader.create(
 *         LuckPermsMinestomOptions.builder()
 *                 .dataDirectory(Path.of("run", "luckperms"))
 *                 .build())
 *         .load()
 *         .enable();
 *
 * MinecraftServer.getSchedulerManager().buildShutdownTask(luckPerms::close);
 * server.start("0.0.0.0", 25565);
 * }</pre>
 */
public final class MinestomLoader implements LuckPermsMinestomHandle {

    private static final String JAR_NAME = "luckperms-minestom.jarinjar";
    private static final String BOOTSTRAP_CLASS = "me.lucko.luckperms.minestom.LPMinestomBootstrap";

    private final LuckPermsMinestomOptions options;
    private final JarInJarClassLoader classLoader;
    private final LoaderBootstrap plugin;

    private boolean loaded;
    private boolean enabled;
    private boolean closed;
    private Thread shutdownHook;

    private MinestomLoader(LuckPermsMinestomOptions options) {
        this.options = options;

        LuckPermsMinestomInstanceLock.acquire(this);

        JarInJarClassLoader classLoader = null;
        try {
            classLoader = new JarInJarClassLoader(getClass().getClassLoader(), JAR_NAME);
            this.plugin = classLoader.instantiatePlugin(BOOTSTRAP_CLASS, LuckPermsApplication.class,
                    new LuckPermsApplication(options));
            this.classLoader = classLoader;
        } catch (RuntimeException | Error e) {
            closeQuietly(classLoader);
            LuckPermsMinestomInstanceLock.release(this);
            throw e;
        }
    }

    /**
     * Creates a handle with fully default options.
     *
     * @return the handle, not yet loaded
     */
    public static LuckPermsMinestomHandle create() {
        return create(LuckPermsMinestomOptions.defaults());
    }

    /**
     * Creates a handle.
     *
     * <p>This replaces the former {@code MinestomLoader.get()} singleton. The old
     * call chain {@code MinestomLoader.get().load().registerShutdownHook().start()}
     * no longer exists: there is no process-wide singleton to reuse, the shutdown
     * hook is an option rather than a call, and the returned handle can actually
     * be shut down again.</p>
     *
     * @param options the options
     * @return the handle, not yet loaded
     * @throws IllegalStateException if another LuckPerms instance is already
     *                               running in this process
     */
    public static LuckPermsMinestomHandle create(LuckPermsMinestomOptions options) {
        Objects.requireNonNull(options, "options");
        return new MinestomLoader(options);
    }

    @Override
    public LuckPermsMinestomOptions options() {
        return this.options;
    }

    @Override
    public LuckPermsMinestomHandle load() {
        ensureOpen();
        if (this.loaded) {
            throw new IllegalStateException("LuckPerms is already loaded");
        }
        this.plugin.onLoad();
        this.loaded = true;
        return this;
    }

    @Override
    public LuckPermsMinestomHandle enable() {
        ensureOpen();
        if (this.enabled) {
            throw new IllegalStateException("LuckPerms is already enabled");
        }
        if (!this.loaded) {
            load();
        }
        this.plugin.onEnable();
        this.enabled = true;

        if (this.options.registerShutdownHook()) {
            this.shutdownHook = new Thread(this::close, "luckperms-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        }
        return this;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled && !this.closed;
    }

    /**
     * Shuts LuckPerms down and closes the nested class loader.
     *
     * <p>The class loader used to be a local variable that was dropped on the
     * floor after the constructor, so both it and the temp file it maps stayed
     * alive for the lifetime of the JVM.</p>
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        removeShutdownHook();
        try {
            if (this.enabled) {
                this.plugin.onDisable();
            }
        } finally {
            this.enabled = false;
            closeQuietly(this.classLoader);
            LuckPermsMinestomInstanceLock.release(this);
        }
    }

    private void removeShutdownHook() {
        Thread hook = this.shutdownHook;
        this.shutdownHook = null;
        if (hook == null || hook == Thread.currentThread()) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // shutdown already in progress
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("This LuckPerms handle has been closed");
        }
    }

    private static void closeQuietly(JarInJarClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException e) {
            // nothing useful to do about it at this point
        }
    }
}
