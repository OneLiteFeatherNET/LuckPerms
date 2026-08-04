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

import me.lucko.luckperms.common.api.LuckPermsApiProvider;
import me.lucko.luckperms.common.calculator.CalculatorFactory;
import me.lucko.luckperms.common.command.CommandManager;
import me.lucko.luckperms.common.config.generic.adapter.ConfigurationAdapter;
import me.lucko.luckperms.common.context.manager.ContextManager;
import me.lucko.luckperms.common.dependencies.Dependency;
import me.lucko.luckperms.common.dependencies.DependencyManager;
import me.lucko.luckperms.common.dependencies.DependencyManagerImpl;
import me.lucko.luckperms.common.dependencies.DependencyRepository;
import me.lucko.luckperms.common.event.AbstractEventBus;
import me.lucko.luckperms.common.messaging.MessagingFactory;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.Track;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.GroupManager;
import me.lucko.luckperms.common.model.manager.group.StandardGroupManager;
import me.lucko.luckperms.common.model.manager.track.StandardTrackManager;
import me.lucko.luckperms.common.model.manager.track.TrackManager;
import me.lucko.luckperms.common.model.manager.user.StandardUserManager;
import me.lucko.luckperms.common.model.manager.user.UserManager;
import me.lucko.luckperms.common.plugin.AbstractLuckPermsPlugin;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.tasks.CacheHousekeepingTask;
import me.lucko.luckperms.common.tasks.ExpireTemporaryTask;
import me.lucko.luckperms.minestom.calculator.MinestomCalculatorFactory;
import me.lucko.luckperms.minestom.context.MinestomContextManager;
import me.lucko.luckperms.minestom.context.MinestomPlayerCalculator;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomOptions;
import me.lucko.luckperms.minestom.listener.MinestomConnectionListener;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.query.QueryOptions;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class LPMinestomPlugin extends AbstractLuckPermsPlugin {
    private final LPMinestomBootstrap bootstrap;

    /**
     * Resolved lazily rather than in a static initialiser.
     *
     * <p>It used to be {@code private static final ConsoleSender DELEGATED_CONSOLE =
     * MinecraftServer.getCommandManager().getConsoleSender()}, which forced
     * {@code MinecraftServer.init()} to have run before this class could even be
     * <i>loaded</i>. Since the bootstrap constructs the plugin in its own
     * constructor, merely creating a handle too early produced an
     * {@code ExceptionInInitializerError} from deep inside the reflection path,
     * with nothing in it that pointed at the real cause.</p>
     */
    private ConsoleSender delegatedConsole;

    private MinestomSenderFactory senderFactory;
    private MinestomContextManager contextManager;
    private StandardUserManager userManager;
    private StandardGroupManager groupManager;
    private StandardTrackManager trackManager;
    private MinestomConnectionListener connectionListener;
    private MinestomCommandExecutor commandExecutor;

    public LPMinestomPlugin(LPMinestomBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    private LuckPermsMinestomOptions options() {
        return this.bootstrap.getOptions();
    }

    /**
     * Chooses how runtime dependencies are obtained.
     *
     * <p>{@code createDependencyManager()} is {@code protected} in
     * {@code AbstractLuckPermsPlugin}, so this override costs no change in
     * {@code common/}.</p>
     *
     * <p>{@link me.lucko.luckperms.minestom.app.DependencyMode#DOWNLOAD} is the
     * default and delegates to {@code super}, i.e. it is literally the upstream
     * behaviour. The other two modes exist because Minestom offers no way to add
     * URLs to a class loader at runtime: when LuckPerms is shaded flat into a
     * host's fat jar there is nothing to append to, so everything has to be
     * there already.</p>
     */
    @Override
    protected DependencyManager createDependencyManager() {
        return switch (options().dependencyMode()) {
            case DOWNLOAD -> super.createDependencyManager();
            case JAR_IN_JAR -> new DependencyManagerImpl(this, List.of(DependencyRepository.JAR_IN_JAR));
            case PRELOADED -> new PreloadedDependencyManager();
        };
    }

    @Override
    protected Set<Dependency> getGlobalDependencies() {
        return EnumSet.of(
                Dependency.CAFFEINE,
                Dependency.OKIO,
                Dependency.OKHTTP,
                Dependency.BYTEBUDDY,
                Dependency.EVENT,
                Dependency.CONFIGURATE_CORE,
                Dependency.CONFIGURATE_YAML,
                Dependency.SNAKEYAML
        );
    }

    @Override
    protected void setupSenderFactory() {
        if (MinecraftServer.process() == null) {
            throw new IllegalStateException(
                    "MinecraftServer.init() has not been called yet. LuckPerms needs a running Minestom " +
                    "process to reach the console sender, the command manager and the event tree, so it has " +
                    "to be enabled after MinecraftServer.init().");
        }
        this.delegatedConsole = MinecraftServer.getCommandManager().getConsoleSender();
        this.senderFactory = new MinestomSenderFactory(this);
    }

    public MinestomSenderFactory getSenderFactory() {
        return senderFactory;
    }

    @Override
    protected ConfigurationAdapter provideConfigurationAdapter() {
        return new MinestomConfigAdapter(this, resolveConfig("config.yml"));
    }

    @Override
    protected void registerPlatformListeners() {
        this.connectionListener = new MinestomConnectionListener(this);
        this.connectionListener.registerListeners();
    }

    @Override
    protected MessagingFactory<?> provideMessagingFactory() {
        return new MessagingFactory<>(this);
    }

    @Override
    protected void registerCommands() {
        this.commandExecutor = new MinestomCommandExecutor(this, options().commandAliases());
        this.commandExecutor.register();
    }

    @Override
    protected void setupManagers() {
        this.userManager = new StandardUserManager(this);
        this.groupManager = new StandardGroupManager(this);
        this.trackManager = new StandardTrackManager(this);
    }

    @Override
    protected CalculatorFactory provideCalculatorFactory() {
        return new MinestomCalculatorFactory(this);
    }

    @Override
    protected void setupContextManager() {
        this.contextManager = new MinestomContextManager(this);

        MinestomPlayerCalculator playerCalculator = new MinestomPlayerCalculator();
        this.contextManager.registerCalculator(playerCalculator);
    }

    @Override
    protected void setupPlatformHooks() {
        // Minestom has no permission system of its own, so there is nothing to
        // hook into. Hosts reach LuckPerms through
        // me.lucko.luckperms.minestom.app.LuckPermsCommandConditions, which
        // resolves the running instance lazily via LuckPermsProvider and
        // therefore needs no registration from here.
    }

    @Override
    protected AbstractEventBus<?> provideEventBus(LuckPermsApiProvider apiProvider) {
        return new MinestomEventBus(this, apiProvider);
    }

    @Override
    protected void registerApiOnPlatform(LuckPerms api) {
        // Minestom doesn't have a services manager
    }

    /**
     * Undoes everything {@code enable()} did to the host.
     *
     * <p>Called by {@code AbstractLuckPermsPlugin#disable()}. Without this
     * override (the base implementation is empty) {@code /lp} and the connection
     * listeners stayed attached to the host after a shutdown, which made a
     * restart in the same process impossible and leaked into every test.</p>
     */
    @Override
    protected void removePlatformHooks() {
        if (this.commandExecutor != null) {
            this.commandExecutor.unregister();
        }
        if (this.connectionListener != null) {
            this.connectionListener.unregisterListeners();
        }
    }

    /**
     * Logs one line summarising the state a support request would otherwise have
     * to guess at.
     *
     * <p>"Permissions do not apply" is normally diagnosed with {@code /lp
     * verbose} - which requires the very permission that is missing. This line
     * is the only thing available before that point, so it names the four things
     * that actually go wrong: the wrong data directory (so a config edit had no
     * effect), the dependency mode (which decides how a startup failure looks),
     * whether player checks are answered by LuckPerms at all, and the command
     * names that were really registered.</p>
     */
    @Override
    protected void performFinalSetup() {
        LuckPermsMinestomOptions options = options();

        String aliases = this.commandExecutor == null || this.commandExecutor.getAliases().isEmpty()
                ? "<none registered>"
                : String.join(", ", this.commandExecutor.getAliases());

        getLogger().info("Minestom integration ready" +
                " | data directory: " + options.dataDirectory() + " (from " + options.dataDirectoryOrigin() + ")" +
                " | dependencies: " + options.dependencyMode() +
                " | permission bridge: " + (this.senderFactory != null
                        ? "active (player checks answered by LuckPerms itself)"
                        : "INACTIVE") +
                " | commands: " + aliases +
                " | event node: " + (this.connectionListener != null && this.connectionListener.getEventNode() != null
                        ? MinestomConnectionListener.EVENT_NODE_NAME
                        : "NOT ATTACHED") +
                " | shutdown hook: " + (options.registerShutdownHook() ? "registered" : "off"));

        if (!options.registerShutdownHook()) {
            getLogger().info("No JVM shutdown hook is registered. Call LuckPermsMinestomHandle#close() from the " +
                    "host lifecycle (e.g. MinecraftServer.getSchedulerManager().buildShutdownTask(handle::close)), " +
                    "otherwise pending writes are lost when the process ends.");
        }
    }

    @Override
    protected void registerHousekeepingTasks() {
        this.bootstrap.getScheduler().asyncRepeating(new ExpireTemporaryTask(this), 3, TimeUnit.SECONDS);
        this.bootstrap.getScheduler().asyncRepeating(new CacheHousekeepingTask(this), 2, TimeUnit.MINUTES);
    }

    @Override
    public LPMinestomBootstrap getBootstrap() {
        return this.bootstrap;
    }

    @Override
    public UserManager<? extends User> getUserManager() {
        return this.userManager;
    }

    @Override
    public GroupManager<? extends Group> getGroupManager() {
        return this.groupManager;
    }

    @Override
    public TrackManager<? extends Track> getTrackManager() {
        return this.trackManager;
    }

    @Override
    public CommandManager getCommandManager() {
        return this.commandExecutor;
    }

    @Override
    public AbstractConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    @Override
    public ContextManager<Player, Player> getContextManager() {
        return this.contextManager;
    }

    @Override
    public Optional<QueryOptions> getQueryOptionsForUser(User user) {
        return this.bootstrap.getPlayer(user.getUniqueId()).map(player -> this.contextManager.getQueryOptions(player));
    }

    @Override
    public Stream<Sender> getOnlineSenders() {
        return MinecraftServer.getConnectionManager().getOnlinePlayers().stream().map(getSenderFactory()::wrap);
    }

    @Override
    public Sender getConsoleSender() {
        return getSenderFactory().wrap(this.delegatedConsole);
    }
}
