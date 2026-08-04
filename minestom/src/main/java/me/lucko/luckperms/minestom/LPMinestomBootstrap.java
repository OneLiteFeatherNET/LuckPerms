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

import me.lucko.luckperms.common.loader.JarInJarClassLoader;
import me.lucko.luckperms.common.loader.LoaderBootstrap;
import me.lucko.luckperms.common.plugin.bootstrap.BootstrappedWithLoader;
import me.lucko.luckperms.common.plugin.bootstrap.LuckPermsBootstrap;
import me.lucko.luckperms.common.plugin.classpath.ClassPathAppender;
import me.lucko.luckperms.common.plugin.classpath.JarInJarClassPathAppender;
import me.lucko.luckperms.common.plugin.logging.PluginLogger;
import me.lucko.luckperms.common.plugin.logging.Slf4jPluginLogger;
import me.lucko.luckperms.common.plugin.scheduler.SchedulerAdapter;
import me.lucko.luckperms.minestom.app.DependencyMode;
import me.lucko.luckperms.minestom.app.LuckPermsApplication;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomOptions;
import net.luckperms.api.platform.Platform;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

public class LPMinestomBootstrap implements LuckPermsBootstrap, LoaderBootstrap, BootstrappedWithLoader {

    // Latches for enable and load
    private final CountDownLatch loadLatch = new CountDownLatch(1);
    private final CountDownLatch enableLatch = new CountDownLatch(1);

    /**
     * The plugin instance
     */
    private final LuckPermsApplication loader;
    private final LuckPermsMinestomOptions options;
    private final Slf4jPluginLogger logger;
    private final LPMinestomPlugin plugin;
    private final MinestomSchedulerAdapter schedulerAdapter;
    private final ClassPathAppender classPathAppender;

    private Instant startupTime;

    public LPMinestomBootstrap(LuckPermsApplication loader) {
        this.loader = loader;
        this.options = loader.getOptions();
        this.logger = new Slf4jPluginLogger(this.options.logger());
        this.classPathAppender = createClassPathAppender(getClass().getClassLoader(), this.options, this.logger);
        this.plugin = new LPMinestomPlugin(this);
        this.schedulerAdapter = new MinestomSchedulerAdapter(this);
    }

    /**
     * @return the options this instance was configured with
     */
    public LuckPermsMinestomOptions getOptions() {
        return this.options;
    }

    /**
     * Creates a {@link ClassPathAppender} for the given class loader.
     *
     * <p>When LuckPerms is started through the JarInJar loader the regular
     * {@link JarInJarClassPathAppender} is used. When it is shaded flat into a
     * consumer's fat jar it runs under the app class loader instead - the
     * JarInJar appender would throw {@link IllegalArgumentException} there, so a
     * no-op appender is used.</p>
     *
     * <p><b>Invariant, enforced here:</b> a no-op appender is only correct as
     * long as LuckPerms does not have to load anything at runtime. Combining it
     * with {@link DependencyMode#DOWNLOAD} or {@link DependencyMode#JAR_IN_JAR}
     * is a silent total failure - the jars are downloaded, verified, relocated
     * and then dropped on the floor, and the first class that needs them fails
     * with a {@code NoClassDefFoundError} that points nowhere near the cause.
     * That combination is rejected outright.</p>
     */
    private static ClassPathAppender createClassPathAppender(ClassLoader classLoader, LuckPermsMinestomOptions options, PluginLogger logger) {
        try {
            if (classLoader instanceof JarInJarClassLoader) {
                return new JarInJarClassPathAppender(classLoader);
            }
        } catch (LinkageError e) {
            // loader-utils is not on the class path at all - definitely not JarInJar
        }

        if (options.dependencyMode() != DependencyMode.PRELOADED) {
            throw new IllegalStateException(
                    "LuckPerms is running under " + classLoader.getClass().getName() + ", not a " +
                    "JarInJarClassLoader, so it has no way to add jars to the class path at runtime - but " +
                    "DependencyMode." + options.dependencyMode() + " requires exactly that. Dependencies would " +
                    "be resolved and then silently discarded, and LuckPerms would fail later with an " +
                    "unrelated-looking NoClassDefFoundError.\n" +
                    "Use DependencyMode.PRELOADED and make sure every LuckPerms dependency is on the class " +
                    "path (this is what the flat library packaging does), or run LuckPerms through the " +
                    "JarInJar loader (MinestomLoader.create(options)).");
        }

        logger.info("Running outside a JarInJarClassLoader (" + classLoader.getClass().getName() +
                "); dependencies are expected to be on the class path already.");
        return file -> {}; // ClassPathAppender is a functional interface
    }

    @Override
    public void onLoad() {
        this.startupTime = Instant.now();
        try {
            this.plugin.load();
        } finally {
            this.loadLatch.countDown();
        }
    }

    @Override
    public void onEnable() {
        if (this.startupTime == null) {
            this.startupTime = Instant.now();
        }
        try {
            this.plugin.enable();
        } finally {
            this.enableLatch.countDown();
        }
    }

    @Override
    public void onDisable() {
        this.plugin.disable();
    }

    @Override
    public PluginLogger getPluginLogger() {
        return this.logger;
    }

    @Override
    public SchedulerAdapter getScheduler() {
        return schedulerAdapter;
    }

    @Override
    public ClassPathAppender getClassPathAppender() {
        return classPathAppender;
    }

    @Override
    public CountDownLatch getLoadLatch() {
        return this.loadLatch;
    }

    @Override
    public CountDownLatch getEnableLatch() {
        return this.enableLatch;
    }

    @Override
    public String getVersion() {
        return this.loader.getVersion();
    }

    @Override
    public Instant getStartupTime() {
        return startupTime;
    }

    @Override
    public Platform.Type getType() {
        return Platform.Type.MINESTOM;
    }

    @Override
    public String getServerBrand() {
        return MinecraftServer.getBrandName();
    }

    @Override
    public String getServerVersion() {
        return MinecraftServer.VERSION_NAME;
    }

    /**
     * The directory LuckPerms keeps config.yml, its storage and downloaded files
     * in.
     *
     * <p>This used to be a hard-coded {@code Paths.get("data")} inherited from
     * the standalone platform, which meant LuckPerms unconditionally wrote into
     * the working directory of a foreign server process. It now comes from
     * {@link LuckPermsMinestomOptions}, which resolves it from the builder, the
     * {@code luckperms.data-dir} system property, the {@code LUCKPERMS_DATA_DIR}
     * environment variable or the old default, in that order.</p>
     */
    @Override
    public Path getDataDirectory() {
        return this.options.dataDirectory();
    }

    @Override
    public Optional<Player> getPlayer(UUID uniqueId) {
        return Optional.ofNullable(MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uniqueId));
    }

    @Override
    public Optional<UUID> lookupUniqueId(String username) {
        Player player = MinecraftServer.getConnectionManager().findOnlinePlayer(username);

        if (player == null) return Optional.empty();

        return Optional.of(player.getUuid());
    }

    @Override
    public Optional<String> lookupUsername(UUID uniqueId) {
        Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uniqueId);

        if (player == null) return Optional.empty();

        return Optional.of(player.getUsername());
    }

    @Override
    public int getPlayerCount() {
        return MinecraftServer.getConnectionManager().getOnlinePlayers().size();
    }

    @Override
    public Collection<String> getPlayerList() {
        ArrayList<String> playerNames = new ArrayList<>();
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> playerNames.add(player.getUsername()));
        return playerNames;
    }

    @Override
    public Collection<UUID> getOnlinePlayers() {
        ArrayList<UUID> playerIds = new ArrayList<>();
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> playerIds.add(player.getUuid()));
        return playerIds;
    }

    @Override
    public boolean isPlayerOnline(UUID uniqueId) {
        return MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uniqueId) != null;
    }

    @Override
    public Object getLoader() {
        return this.loader;
    }
}
