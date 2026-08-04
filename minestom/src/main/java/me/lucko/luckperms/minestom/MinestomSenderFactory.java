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

import me.lucko.luckperms.common.locale.TranslationManager;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.sender.SenderFactory;
import me.lucko.luckperms.common.verbose.event.CheckOrigin;
import net.kyori.adventure.permission.PermissionChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.entity.Player;

import java.util.Locale;
import java.util.UUID;

public class MinestomSenderFactory extends SenderFactory<LPMinestomPlugin, CommandSender> {

    public MinestomSenderFactory(LPMinestomPlugin plugin) {
        super(plugin);
    }

    @Override
    protected UUID getUniqueId(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUuid();
        } else {
            return Sender.CONSOLE_UUID;
        }
    }

    @Override
    protected String getName(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUsername();
        } else {
            return Sender.CONSOLE_NAME;
        }
    }

    @Override
    protected void sendMessage(CommandSender sender, Component message) {
        Locale locale = null;
        if (sender instanceof Player player) {
            locale = player.getSettings().locale();
        }
        Component rendered = TranslationManager.render(message, locale);
        sender.sendMessage(rendered);
    }

    /**
     * Resolves a permission for an arbitrary Minestom {@link CommandSender}.
     *
     * <p>Minestom has no string permission system of its own - {@code Player}
     * only knows numeric permission levels, and
     * {@code Player.PLAYER_POINTERS_SUPPLIER} is {@code protected static final}
     * and resolves nothing but NAME/DISPLAY_NAME/LOCALE. Nobody ever populates
     * {@link PermissionChecker#POINTER}, so reading it for players would make
     * every check constantly {@code FALSE}.</p>
     *
     * <p>The bridge therefore runs the other way round: for players LuckPerms
     * asks itself. The Adventure pointer is only consulted for sender types we
     * do not know - and then via {@code value()} rather than {@code test()},
     * because {@code test()} is the inherited {@code Predicate} method and
     * collapses {@code NOT_SET} into {@code FALSE}.</p>
     */
    @Override
    protected Tristate getPermissionValue(CommandSender sender, String node) {
        if (sender instanceof Player player) {
            return getPlayerPermissionValue(player, node);
        }

        if (sender instanceof ConsoleSender) {
            return Tristate.TRUE;
        }

        TriState value = sender
                .getOrDefault(PermissionChecker.POINTER, PermissionChecker.always(TriState.NOT_SET))
                .value(node);

        return switch (value) {
            case TRUE -> Tristate.TRUE;
            case FALSE -> Tristate.FALSE;
            case NOT_SET -> Tristate.UNDEFINED;
        };
    }

    private Tristate getPlayerPermissionValue(Player player, String node) {
        LPMinestomPlugin plugin = getPlugin();

        User user = plugin.getUserManager().getIfLoaded(player.getUuid());
        if (user == null) {
            // The user has not been loaded (yet). This is not the same thing as
            // "has no permissions" - make it visible instead of silently denying.
            plugin.getLogger().warn("Permission check for '" + node + "' by " + player.getUsername() + " (" +
                    player.getUuid() + ") could not be answered: the user is not loaded. Denying.");
            return Tristate.FALSE;
        }

        QueryOptions queryOptions = plugin.getContextManager().getQueryOptions(player);
        return user.getCachedData()
                .getPermissionData(queryOptions)
                .checkPermission(node, CheckOrigin.PLATFORM_API_HAS_PERMISSION)
                .result();
    }

    @Override
    protected boolean hasPermission(CommandSender sender, String node) {
        return getPermissionValue(sender, node).asBoolean();
    }

    @Override
    protected void performCommand(CommandSender sender, String command) {
        MinecraftServer.getCommandManager().execute(sender, command);
    }

    @Override
    protected boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleSender;
    }
}
