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

import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration for an embedded LuckPerms instance.
 *
 * <p>This class is the reason {@code minestom/app} exists as a separate module:
 * it is the contract layer, visible on <i>both</i> sides of the class loader
 * boundary. On the JarInJar route the host constructs the options on its own
 * application class loader and they are handed to the platform code inside the
 * nested jar through {@link LuckPermsApplication}; because the JarInJar loader
 * delegates parent-first, both sides resolve the very same class.</p>
 *
 * <p>Everything here used to be hard-coded, which is why none of it could be
 * changed without a rebuild of LuckPerms itself.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * LuckPermsMinestomOptions options = LuckPermsMinestomOptions.builder()
 *         .dataDirectory(Path.of("run", "luckperms"))
 *         .logger(LoggerFactory.getLogger("myserver.permissions"))
 *         .commandAliases(List.of("luckperms", "lp"))
 *         .dependencyMode(DependencyMode.DOWNLOAD)
 *         .registerShutdownHook(false)
 *         .build();
 * }</pre>
 */
public final class LuckPermsMinestomOptions {

    /**
     * System property which overrides the data directory.
     *
     * <p>Ops teams need to be able to move the directory without rebuilding the
     * host, so this takes precedence over the built-in default - but not over an
     * explicit {@link Builder#dataDirectory(Path)}, which is a deliberate
     * decision made in code.</p>
     */
    public static final String DATA_DIRECTORY_PROPERTY = "luckperms.data-dir";

    /**
     * Environment variable equivalent of {@link #DATA_DIRECTORY_PROPERTY}.
     */
    public static final String DATA_DIRECTORY_ENV = "LUCKPERMS_DATA_DIR";

    /**
     * The directory used when nothing else specifies one. Matches the value that
     * used to be hard-coded in the bootstrap.
     */
    public static final String DEFAULT_DATA_DIRECTORY = "data";

    /**
     * The commands registered when the host does not say otherwise.
     *
     * <p>Deliberately much smaller than the six aliases that used to be
     * registered unconditionally: Minestom's {@code CommandManager#register}
     * throws when a name is already taken, so every extra alias is another way
     * for {@code enable()} to abort on a host that happens to use that name.</p>
     */
    public static final List<String> DEFAULT_COMMAND_ALIASES = List.of("luckperms", "lp");

    private final Path dataDirectory;
    private final String dataDirectoryOrigin;
    private final Logger logger;
    private final List<String> commandAliases;
    private final boolean registerShutdownHook;
    private final DependencyMode dependencyMode;
    private final EventNode<Event> eventNode;

    private LuckPermsMinestomOptions(Builder builder) {
        DirectorySelection selection = resolveDataDirectory(builder.dataDirectory);
        this.dataDirectory = selection.path;
        this.dataDirectoryOrigin = selection.origin;
        this.logger = builder.logger != null ? builder.logger : LoggerFactory.getLogger("luckperms");
        this.commandAliases = builder.commandAliases != null ? builder.commandAliases : DEFAULT_COMMAND_ALIASES;
        this.registerShutdownHook = builder.registerShutdownHook;
        this.dependencyMode = builder.dependencyMode;
        this.eventNode = builder.eventNode;
    }

    /**
     * @return a new builder with every option on its default
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the options every setting left on its default produces
     */
    public static LuckPermsMinestomOptions defaults() {
        return builder().build();
    }

    /**
     * The directory LuckPerms keeps its configuration, storage and downloaded
     * files in. Always absolute and already resolved - see
     * {@link #dataDirectoryOrigin()} for where the value came from.
     *
     * @return the resolved data directory
     */
    public Path dataDirectory() {
        return this.dataDirectory;
    }

    /**
     * Describes which of the four sources supplied {@link #dataDirectory()}:
     * {@code "builder"}, {@code "system property luckperms.data-dir"},
     * {@code "environment variable LUCKPERMS_DATA_DIR"} or {@code "default"}.
     *
     * <p>Purely diagnostic - "LuckPerms wrote its config somewhere I did not
     * expect" is otherwise impossible to tell apart from "my setting was
     * ignored".</p>
     *
     * @return a human readable description of where the data directory came from
     */
    public String dataDirectoryOrigin() {
        return this.dataDirectoryOrigin;
    }

    /**
     * @return the logger LuckPerms writes to
     */
    public Logger logger() {
        return this.logger;
    }

    /**
     * The command names to register. The first entry is the command's primary
     * name, the rest are aliases. An empty list disables command registration
     * entirely.
     *
     * @return an immutable list of command names
     */
    public List<String> commandAliases() {
        return this.commandAliases;
    }

    /**
     * Whether a JVM shutdown hook should be installed which disables LuckPerms
     * when the process ends.
     *
     * <p>Defaults to {@code false}. The ordering of a JVM hook relative to the
     * host's own shutdown is undefined, so the supported way to shut LuckPerms
     * down is to call {@link LuckPermsMinestomHandle#close()} from inside the
     * host lifecycle, e.g.
     * {@code MinecraftServer.getSchedulerManager().buildShutdownTask(handle::close)}.</p>
     *
     * @return true if a JVM shutdown hook should be registered
     */
    public boolean registerShutdownHook() {
        return this.registerShutdownHook;
    }

    /**
     * @return how runtime dependencies are obtained
     */
    public DependencyMode dependencyMode() {
        return this.dependencyMode;
    }

    /**
     * The event node LuckPerms attaches its own child node to, or {@code null}
     * to use the global event handler.
     *
     * <p>LuckPerms always creates a child node of its own so that
     * {@link LuckPermsMinestomHandle#close()} can detach every listener in one
     * go - Minestom's {@code addListener(Class, Consumer)} overload does not
     * hand back the listener instance, so individual removal is impossible.</p>
     *
     * @return the parent event node, or null for the global event handler
     */
    public EventNode<Event> eventNode() {
        return this.eventNode;
    }

    @Override
    public String toString() {
        return "LuckPermsMinestomOptions{" +
                "dataDirectory=" + this.dataDirectory +
                " (" + this.dataDirectoryOrigin + ")" +
                ", commandAliases=" + this.commandAliases +
                ", dependencyMode=" + this.dependencyMode +
                ", registerShutdownHook=" + this.registerShutdownHook +
                ", eventNode=" + (this.eventNode == null ? "<global>" : this.eventNode.getName()) +
                '}';
    }

    private static DirectorySelection resolveDataDirectory(Path explicit) {
        if (explicit != null) {
            return new DirectorySelection(explicit.toAbsolutePath().normalize(), "builder");
        }

        String property = trimToNull(System.getProperty(DATA_DIRECTORY_PROPERTY));
        if (property != null) {
            return new DirectorySelection(Paths.get(property).toAbsolutePath().normalize(),
                    "system property " + DATA_DIRECTORY_PROPERTY);
        }

        String env = trimToNull(System.getenv(DATA_DIRECTORY_ENV));
        if (env != null) {
            return new DirectorySelection(Paths.get(env).toAbsolutePath().normalize(),
                    "environment variable " + DATA_DIRECTORY_ENV);
        }

        return new DirectorySelection(Paths.get(DEFAULT_DATA_DIRECTORY).toAbsolutePath().normalize(), "default");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record DirectorySelection(Path path, String origin) {}

    /**
     * Builder for {@link LuckPermsMinestomOptions}. Not thread safe; every
     * setter returns {@code this}.
     */
    public static final class Builder {
        private Path dataDirectory;
        private Logger logger;
        private List<String> commandAliases;
        private boolean registerShutdownHook = false;
        private DependencyMode dependencyMode = DependencyMode.DOWNLOAD;
        private EventNode<Event> eventNode;

        private Builder() {
        }

        /**
         * Sets the data directory explicitly. This wins over
         * {@value #DATA_DIRECTORY_PROPERTY} and {@value #DATA_DIRECTORY_ENV};
         * pass {@code null} to fall back to them.
         *
         * @param dataDirectory the directory, relative paths are resolved
         *                      against the working directory
         * @return this builder
         */
        public Builder dataDirectory(Path dataDirectory) {
            this.dataDirectory = dataDirectory;
            return this;
        }

        /**
         * Sets the logger LuckPerms writes to. Defaults to
         * {@code LoggerFactory.getLogger("luckperms")}.
         *
         * @param logger the logger
         * @return this builder
         */
        public Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        /**
         * Sets the command names to register, primary name first.
         *
         * <p>Pass an empty list to skip command registration - useful for a host
         * that exposes LuckPerms through its own command framework.</p>
         *
         * @param commandAliases the command names, must not contain nulls or blanks
         * @return this builder
         */
        public Builder commandAliases(List<String> commandAliases) {
            Objects.requireNonNull(commandAliases, "commandAliases");

            // Preserve order, drop duplicates: Minestom rejects a Command whose
            // alias array repeats a name.
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String alias : commandAliases) {
                Objects.requireNonNull(alias, "commandAliases element");
                String trimmed = alias.trim();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("commandAliases must not contain blank entries");
                }
                unique.add(trimmed);
            }
            this.commandAliases = List.copyOf(new ArrayList<>(unique));
            return this;
        }

        /**
         * Enables or disables the JVM shutdown hook. Defaults to {@code false}.
         *
         * @param registerShutdownHook whether to register a shutdown hook
         * @return this builder
         * @see LuckPermsMinestomOptions#registerShutdownHook()
         */
        public Builder registerShutdownHook(boolean registerShutdownHook) {
            this.registerShutdownHook = registerShutdownHook;
            return this;
        }

        /**
         * Sets how runtime dependencies are obtained. Defaults to
         * {@link DependencyMode#DOWNLOAD}.
         *
         * @param dependencyMode the dependency mode
         * @return this builder
         */
        public Builder dependencyMode(DependencyMode dependencyMode) {
            this.dependencyMode = Objects.requireNonNull(dependencyMode, "dependencyMode");
            return this;
        }

        /**
         * Sets the parent event node LuckPerms attaches its listeners to.
         * Defaults to the global event handler.
         *
         * @param eventNode the parent node, or null for the global event handler
         * @return this builder
         */
        public Builder eventNode(EventNode<Event> eventNode) {
            this.eventNode = eventNode;
            return this;
        }

        /**
         * @return the immutable options
         */
        public LuckPermsMinestomOptions build() {
            return new LuckPermsMinestomOptions(this);
        }
    }
}
