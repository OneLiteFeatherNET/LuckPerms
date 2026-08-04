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

import net.kyori.adventure.permission.PermissionChecker;
import net.kyori.adventure.util.TriState;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.condition.CommandCondition;
import net.minestom.server.entity.Player;

import java.util.Objects;

/**
 * Factory for Minestom {@link CommandCondition}s backed by LuckPerms.
 *
 * <p>Minestom itself has no string permission system, so
 * {@code Command#setCondition(CommandCondition)} is the only place a host can
 * hook permission checks into its own commands. This class provides that hook.</p>
 *
 * <p><b>Why this class lives in {@code minestom/app}.</b> That module is the
 * contract layer: the only LuckPerms module which is visible on <i>both</i>
 * sides of the class loader boundary. On the loader route the LuckPerms
 * implementation is loaded by a {@code JarInJarClassLoader} whose parent is the
 * host's application class loader; only the outer loader jar - which contains
 * this module and {@code net.luckperms:api}, but not the platform
 * implementation - is on that parent. A host can therefore reference this class
 * directly, and because the JarInJar loader delegates parent-first, the
 * implementation inside the nested jar resolves the very same class.</p>
 *
 * <p>For that reason this class deliberately touches nothing but
 * {@code net.luckperms:api}, Minestom and Adventure. Referencing any
 * {@code me.lucko.luckperms.common.*} or {@code me.lucko.luckperms.minestom.*}
 * type here would drag it back inside the nested jar and make it unreachable
 * for hosts again.</p>
 *
 * <p>The returned conditions resolve LuckPerms <b>lazily, on every single
 * invocation</b>, through {@link LuckPermsProvider}. That is deliberate: a host
 * typically builds and registers its commands during startup, long before
 * LuckPerms has been enabled. A condition created before LuckPerms is up
 * therefore stays valid and simply starts answering correctly once LuckPerms is
 * enabled.</p>
 *
 * <p>While LuckPerms is not available the conditions fall back to
 * "console only" - the console is unconditionally permitted on every LuckPerms
 * platform, everyone else is denied. This is fail-closed by design, and it
 * never throws.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Command command = new Command("fly");
 * command.setCondition(LuckPermsCommandConditions.permission("myserver.command.fly"));
 * MinecraftServer.getCommandManager().register(command);
 * }</pre>
 */
public final class LuckPermsCommandConditions {

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
     * Checks a permission for the given sender against the currently running
     * LuckPerms instance.
     *
     * <p>Players are resolved through the LuckPerms API with the query options
     * that are currently active for them. Senders which are neither a player nor
     * the console are asked via Adventure's {@link PermissionChecker} pointer,
     * mirroring what the platform's own sender bridge does.</p>
     *
     * @param sender     the sender to check
     * @param permission the permission node to check
     * @return true if the sender has the permission, false if it does not or if
     *         LuckPerms is not currently running
     */
    public static boolean hasPermission(CommandSender sender, String permission) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(permission, "permission");

        LuckPerms luckPerms = luckPerms();
        if (luckPerms == null) {
            // LuckPerms is not (yet) enabled - fail closed, but keep the console
            // usable, which matches how LuckPerms treats the console everywhere.
            return sender instanceof ConsoleSender;
        }

        if (sender instanceof Player player) {
            User user = luckPerms.getUserManager().getUser(player.getUuid());
            if (user == null) {
                // The user has not been loaded (yet). That is not the same thing
                // as "has no permissions" - deny, but do not pretend to know.
                return false;
            }

            ContextManager contextManager = luckPerms.getContextManager();
            QueryOptions queryOptions = contextManager.getQueryOptions(user)
                    .orElseGet(contextManager::getStaticQueryOptions);

            return user.getCachedData()
                    .getPermissionData(queryOptions)
                    .checkPermission(permission)
                    .asBoolean();
        }

        if (sender instanceof ConsoleSender) {
            return true;
        }

        // Unknown sender type. Consult the Adventure pointer via value() rather
        // than test(), because test() is the inherited Predicate method and
        // collapses NOT_SET into FALSE - indistinguishable from an explicit
        // denial.
        TriState value = sender
                .getOrDefault(PermissionChecker.POINTER, PermissionChecker.always(TriState.NOT_SET))
                .value(permission);
        return value == TriState.TRUE;
    }

    /**
     * @return true if LuckPerms is currently enabled and able to answer checks
     */
    public static boolean isAvailable() {
        return luckPerms() != null;
    }

    /**
     * Resolves the running LuckPerms instance, or {@code null} if the API is not
     * loaded. {@link LuckPermsProvider#get()} throws an
     * {@link IllegalStateException} in that case, which is not a useful failure
     * mode inside a command condition.
     */
    private static LuckPerms luckPerms() {
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
