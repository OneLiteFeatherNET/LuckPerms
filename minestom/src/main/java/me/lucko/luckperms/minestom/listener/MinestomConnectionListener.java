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

package me.lucko.luckperms.minestom.listener;

import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.locale.Message;
import me.lucko.luckperms.common.locale.TranslationManager;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import me.lucko.luckperms.minestom.LPMinestomPlugin;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;

public class MinestomConnectionListener extends AbstractConnectionListener {

    /**
     * Name of the event node LuckPerms owns. Visible in Minestom's node tree, so
     * it is worth being recognisable.
     */
    public static final String EVENT_NODE_NAME = "luckperms";

    private final LPMinestomPlugin plugin;

    private EventNode<Event> parentNode;
    private EventNode<Event> node;

    public MinestomConnectionListener(LPMinestomPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
    }

    /**
     * Registers every listener on a dedicated child node of the host's event
     * tree.
     *
     * <p>Listeners used to be added straight to the {@code GlobalEventHandler}
     * via {@code addListener(Class, Consumer)}. That overload does not hand back
     * the {@code EventListener} instance, which makes {@code removeListener}
     * impossible - so once registered, LuckPerms' listeners stayed in the host
     * forever, even after a shutdown. Owning a node instead means
     * {@link #unregisterListeners()} can detach all of them at once.</p>
     */
    public void registerListeners() {
        if (this.node != null) {
            throw new IllegalStateException("Listeners are already registered");
        }

        this.parentNode = this.plugin.getBootstrap().getOptions().eventNode();
        if (this.parentNode == null) {
            this.parentNode = MinecraftServer.getGlobalEventHandler();
        }

        EventNode<Event> node = EventNode.all(EVENT_NODE_NAME);
        node.addListener(AsyncPlayerPreLoginEvent.class, this::asyncPreLoginHandler);
        node.addListener(AsyncPlayerConfigurationEvent.class, this::asyncConfigHandler);
        node.addListener(PlayerDisconnectEvent.class, this::disconnectHandler);

        this.parentNode.addChild(node);
        this.node = node;
    }

    /**
     * Detaches the entire event node from the host again. Idempotent.
     */
    public void unregisterListeners() {
        if (this.node == null) {
            return;
        }
        this.parentNode.removeChild(this.node);
        this.node = null;
        this.parentNode = null;
    }

    /**
     * @return the event node LuckPerms owns, or null while nothing is registered
     */
    public EventNode<Event> getEventNode() {
        return this.node;
    }

    private void asyncConfigHandler(AsyncPlayerConfigurationEvent event) {
        final Player player = event.getPlayer();

        if (this.plugin.getConfiguration().get(ConfigKeys.DEBUG_LOGINS)) {
            this.plugin.getLogger().info("Processing login for " + player.getUuid() + " - " + player.getUsername());
        }

        final User user = this.plugin.getUserManager().getIfLoaded(player.getUuid());

        // If the user is null something went badly wrong, so we need to kick them
        if (user == null) {
            this.plugin.getLogger().warn("User " + player.getUuid() + " - " + player.getUsername() + " doesn't have data preloaded - denying login");
            Component kickMsg = TranslationManager.render(Message.LOADING_STATE_ERROR.build());
            player.kick(kickMsg);
        }
        this.plugin.getContextManager().signalContextUpdate(player);
    }

    private void asyncPreLoginHandler(AsyncPlayerPreLoginEvent event) {
        if (this.plugin.getConfiguration().get(ConfigKeys.DEBUG_LOGINS)) {
            this.plugin.getLogger().info("Preparing login for " + event.getPlayerUuid() + " - " + event.getUsername());
        }

        try {
            User user = loadUser(event.getPlayerUuid(), event.getUsername());
            recordConnection(event.getPlayerUuid());
            this.plugin.getEventDispatcher().dispatchPlayerLoginProcess(event.getPlayerUuid(), event.getUsername(), user);
        } catch (Exception e) {
            this.plugin.getLogger().severe("Exception occurred whilst loading data for " + event.getPlayerUuid() + " - " + event.getUsername(), e);
            Component kickMsg = TranslationManager.render(Message.LOADING_DATABASE_ERROR.build());
            event.getConnection().kick(kickMsg);
        }
    }

    /**
     * Unloads a player's data when they leave.
     *
     * <p>This path did not exist at all before: only the two login events were
     * registered, {@code handleDisconnect} was never called, and every user that
     * ever logged in stayed in the user manager for the lifetime of the process
     * along with their transient nodes.</p>
     */
    private void disconnectHandler(PlayerDisconnectEvent event) {
        final Player player = event.getPlayer();
        handleDisconnect(player.getUuid());
    }
}
