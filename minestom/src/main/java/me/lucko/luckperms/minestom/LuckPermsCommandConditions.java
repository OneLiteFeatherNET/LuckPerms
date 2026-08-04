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

import me.lucko.luckperms.common.command.CommandManager;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.condition.CommandCondition;

import java.util.Objects;

/**
 * Factory for Minestom {@link CommandCondition}s backed by LuckPerms.
 *
 * <p>Minestom itself has no string permission system, so
 * {@code Command#setCondition(CommandCondition)} is the only place a host can
 * hook permission checks into its own commands. This class provides that hook.</p>
 *
 * <p>The returned conditions resolve LuckPerms <b>lazily, on every single
 * invocation</b>. That is deliberate: a host typically builds and registers its
 * commands during startup, long before {@code LuckPerms#enable()} has run. A
 * condition created before LuckPerms is up therefore stays valid and simply
 * starts answering correctly once LuckPerms is enabled.</p>
 *
 * <p>While LuckPerms is not available the conditions fall back to
 * "console only" - the console is unconditionally permitted on every LuckPerms
 * platform, everyone else is denied. This is fail-closed by design.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Command command = new Command("fly");
 * command.setCondition(LuckPermsCommandConditions.permission("myserver.command.fly"));
 * MinecraftServer.getCommandManager().register(command);
 * }</pre>
 *
 * <p><b>Class loader caveat.</b> This class lives in the platform module, which
 * ships inside {@code luckperms-minestom.jarinjar}. When LuckPerms is started
 * through {@code net.luckperms:minestom-loader}, the platform classes are loaded
 * by a {@code JarInJarClassLoader} and are therefore <b>not</b> visible to the
 * host's application class loader - a host cannot reference this class directly
 * on that route (verified: {@code Class.forName} throws
 * {@code ClassNotFoundException}). It is directly usable when LuckPerms is
 * shaded flat into the consumer's jar, and internally by {@code /luckperms}.
 * A host-visible entry point for the loader route is still missing.</p>
 */
public final class LuckPermsCommandConditions {

    /**
     * The currently enabled plugin instance, or {@code null} while LuckPerms is
     * not running. Written by {@link LPMinestomPlugin} on enable/disable.
     */
    private static volatile LPMinestomPlugin plugin;

    private LuckPermsCommandConditions() {
        throw new AssertionError();
    }

    /**
     * Creates a {@link CommandCondition} which passes when the sender has the
     * given permission according to LuckPerms.
     *
     * <p>Minestom evaluates the condition both when executing the command and
     * when computing the command's tab-complete visibility on connect (with a
     * {@code null} command string), so a single condition covers both.</p>
     *
     * @param permission the permission node to check
     * @return a lazily resolving command condition
     */
    public static CommandCondition permission(String permission) {
        Objects.requireNonNull(permission, "permission");
        return (sender, commandString) -> hasPermission(sender, permission);
    }

    /**
     * Creates a {@link CommandCondition} which passes when the sender is allowed
     * to use at least one LuckPerms sub-command.
     *
     * <p>This is the condition LuckPerms puts on its own {@code /luckperms}
     * command. It is exposed publicly so a host can reuse it, e.g. for an alias
     * of its own.</p>
     *
     * @return a lazily resolving command condition
     */
    public static CommandCondition anyLuckPermsCommand() {
        return (sender, commandString) -> {
            LPMinestomPlugin instance = plugin;
            if (instance == null) {
                return sender instanceof ConsoleSender;
            }

            CommandManager commandManager = instance.getCommandManager();
            if (commandManager == null) {
                return sender instanceof ConsoleSender;
            }

            return commandManager.hasPermissionForAny(instance.getSenderFactory().wrap(sender));
        };
    }

    /**
     * Checks a permission for the given sender against the currently running
     * LuckPerms instance.
     *
     * @param sender     the sender to check
     * @param permission the permission node to check
     * @return true if the sender has the permission, false if it does not or if
     *         LuckPerms is not currently running
     */
    public static boolean hasPermission(CommandSender sender, String permission) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(permission, "permission");

        LPMinestomPlugin instance = plugin;
        if (instance == null) {
            // LuckPerms is not (yet) enabled - fail closed, but keep the console
            // usable, which matches how LuckPerms treats the console everywhere.
            return sender instanceof ConsoleSender;
        }

        return instance.getSenderFactory().wrap(sender).hasPermission(permission);
    }

    /**
     * @return true if LuckPerms is currently enabled and able to answer checks
     */
    public static boolean isAvailable() {
        return plugin != null;
    }

    static void bind(LPMinestomPlugin instance) {
        plugin = instance;
    }

    static void unbind(LPMinestomPlugin instance) {
        if (plugin == instance) {
            plugin = null;
        }
    }
}
