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

package me.lucko.luckperms.minestom;

import me.lucko.luckperms.minestom.app.DependencyMode;
import me.lucko.luckperms.minestom.app.LuckPermsApplication;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomHandle;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomInstanceLock;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomOptions;

import java.util.Objects;

/**
 * Entry point for hosts that have LuckPerms <b>flat</b> on their class path -
 * shaded into their own fat jar, or simply declared as an ordinary dependency in
 * a test.
 *
 * <p>Unlike {@code MinestomLoader} this constructs the bootstrap directly: no
 * reflection, no nested jar, no second class loader. That also means there is no
 * {@code ClassPathAppender}, so {@link DependencyMode#PRELOADED} is the only
 * mode that works here - every LuckPerms dependency has to be on the class path
 * already. Anything else is rejected with an explanation rather than failing
 * later with a {@code NoClassDefFoundError}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * MinecraftServer server = MinecraftServer.init();
 *
 * LuckPermsMinestomHandle luckPerms = LuckPermsMinestom.create(
 *         LuckPermsMinestomOptions.builder()
 *                 .dataDirectory(Path.of("run", "luckperms"))
 *                 .dependencyMode(DependencyMode.PRELOADED)
 *                 .build());
 *
 * luckPerms.load().enable();
 * MinecraftServer.getSchedulerManager().buildShutdownTask(luckPerms::close);
 * }</pre>
 *
 * @see me.lucko.luckperms.minestom.app.LuckPermsMinestomHandle
 */
public final class LuckPermsMinestom {

    private LuckPermsMinestom() {
        throw new AssertionError();
    }

    /**
     * Shorthand for {@link LuckPermsMinestomOptions#builder()}.
     *
     * @return a new options builder
     */
    public static LuckPermsMinestomOptions.Builder builder() {
        return LuckPermsMinestomOptions.builder();
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
     * <p>This validates the options against the environment before anything is
     * started, and claims the single-instance slot.</p>
     *
     * @param options the options
     * @return the handle, not yet loaded
     * @throws IllegalStateException if another LuckPerms instance is already
     *                               running in this process, or if the chosen
     *                               {@link DependencyMode} cannot work here
     */
    public static LuckPermsMinestomHandle create(LuckPermsMinestomOptions options) {
        Objects.requireNonNull(options, "options");
        return new FlatHandle(options);
    }

    private static final class FlatHandle implements LuckPermsMinestomHandle {
        private final LuckPermsMinestomOptions options;
        private final LPMinestomBootstrap bootstrap;

        private boolean loaded;
        private boolean enabled;
        private boolean closed;
        private Thread shutdownHook;

        FlatHandle(LuckPermsMinestomOptions options) {
            this.options = options;

            LuckPermsMinestomInstanceLock.acquire(this);
            try {
                // The bootstrap constructor resolves the ClassPathAppender and
                // rejects a no-op appender combined with a mode that needs one.
                this.bootstrap = new LPMinestomBootstrap(new LuckPermsApplication(options));
            } catch (RuntimeException | Error e) {
                LuckPermsMinestomInstanceLock.release(this);
                throw e;
            }
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
            this.bootstrap.onLoad();
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
            this.bootstrap.onEnable();
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

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;

            removeShutdownHook();
            try {
                if (this.enabled) {
                    this.bootstrap.onDisable();
                }
            } finally {
                this.enabled = false;
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
    }
}
