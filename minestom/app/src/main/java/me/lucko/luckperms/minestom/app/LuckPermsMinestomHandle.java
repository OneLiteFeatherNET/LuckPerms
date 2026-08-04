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
 * A host's handle on one embedded LuckPerms instance.
 *
 * <p>The lifecycle mirrors LuckPerms' own two-phase startup:</p>
 * <ol>
 *     <li>{@link #load()} - resolve dependencies and read the configuration.</li>
 *     <li>{@link #enable()} - open storage, register listeners and commands,
 *         publish the API. This blocks; it may connect to a database and,
 *         in {@link DependencyMode#DOWNLOAD}, download jars.</li>
 *     <li>{@link #close()} - tear everything down again.</li>
 * </ol>
 *
 * <p>Both packaging routes hand out the same interface: the JarInJar loader
 * ({@code MinestomLoader.create(options)}) and the flat library
 * ({@code LuckPermsMinestom.create(options)}). Host code written against this
 * interface works with either.</p>
 *
 * <p>Only <b>one</b> instance can exist per process. {@code LuckPermsProvider}
 * holds the running API in a static field on the host's class loader, so a
 * second instance would silently replace the first one's provider. Attempting
 * to create one throws instead - see {@link LuckPermsMinestomInstanceLock}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * MinecraftServer server = MinecraftServer.init();   // must happen first
 *
 * LuckPermsMinestomHandle luckPerms = MinestomLoader.create(
 *         LuckPermsMinestomOptions.builder()
 *                 .dataDirectory(Path.of("run", "luckperms"))
 *                 .build());
 *
 * luckPerms.load().enable();
 * MinecraftServer.getSchedulerManager().buildShutdownTask(luckPerms::close);
 *
 * server.start("0.0.0.0", 25565);
 * }</pre>
 */
public interface LuckPermsMinestomHandle extends AutoCloseable {

    /**
     * @return the options this instance was created with, with every default
     *         already resolved
     */
    LuckPermsMinestomOptions options();

    /**
     * Runs the load phase: resolves runtime dependencies and reads the
     * configuration.
     *
     * @return this handle, for chaining
     * @throws IllegalStateException if already loaded or already closed
     */
    LuckPermsMinestomHandle load();

    /**
     * Runs the enable phase. Calls {@link #load()} first if that has not
     * happened yet.
     *
     * <p>This blocks until LuckPerms is fully up.</p>
     *
     * @return this handle, for chaining
     * @throws IllegalStateException if already enabled or already closed
     */
    LuckPermsMinestomHandle enable();

    /**
     * @return true once {@link #enable()} has completed and before
     *         {@link #close()} was called
     */
    boolean isEnabled();

    /**
     * Shuts LuckPerms down and releases everything it holds in the host:
     * the commands are unregistered, the event node is detached, storage and
     * the scheduler are closed, the API provider is unregistered and - on the
     * JarInJar route - the nested class loader is closed.
     *
     * <p>Idempotent: calling it more than once does nothing. Never throws for a
     * handle that was never enabled.</p>
     */
    @Override
    void close();
}
