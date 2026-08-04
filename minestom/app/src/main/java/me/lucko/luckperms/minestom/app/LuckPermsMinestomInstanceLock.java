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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Enforces that at most one LuckPerms instance exists at a time.
 *
 * <p><b>Why this is necessary.</b> {@code ApiRegistrationUtil} publishes the
 * running instance by reflectively writing a {@code static} field on
 * {@code net.luckperms.api.LuckPermsProvider}. That class always resolves to the
 * host's copy - the JarInJar loader delegates parent-first, so even two
 * instances in two separate nested class loaders write to the very same field.
 * A second instance therefore silently overwrites the first one's provider, and
 * the first one's {@code close()} then unregisters the <i>second</i> one. That
 * failure mode is invisible until something reads {@code LuckPermsProvider.get()}
 * and gets an instance belonging to a different server.</p>
 *
 * <p>This class lives in the contract module for exactly the same reason the
 * problem exists: it has to be the same class on both sides of the class loader
 * boundary, and it has to be shared between the JarInJar route and the flat
 * library route.</p>
 *
 * <p>Not part of the supported API surface; it is public only because the two
 * packaging modules live in different packages.</p>
 */
public final class LuckPermsMinestomInstanceLock {

    private static final AtomicReference<Object> OWNER = new AtomicReference<>();

    private LuckPermsMinestomInstanceLock() {
        throw new AssertionError();
    }

    /**
     * Claims the single instance slot.
     *
     * @param owner the object claiming it, used to make {@link #release(Object)}
     *              safe against a stale handle releasing a live one
     * @throws IllegalStateException if another instance already holds the slot
     */
    public static void acquire(Object owner) {
        if (!OWNER.compareAndSet(null, owner)) {
            throw new IllegalStateException(
                    "A LuckPerms instance is already running in this process (held by " +
                    describe(OWNER.get()) + "). LuckPerms publishes itself through a static field on " +
                    "net.luckperms.api.LuckPermsProvider, so a second instance would silently replace the " +
                    "first one's API provider. Close the existing handle before creating another one.");
        }
    }

    /**
     * Releases the slot, but only if {@code owner} is the one holding it.
     *
     * @param owner the object that called {@link #acquire(Object)}
     */
    public static void release(Object owner) {
        OWNER.compareAndSet(owner, null);
    }

    /**
     * @return true if an instance currently holds the slot
     */
    public static boolean isHeld() {
        return OWNER.get() != null;
    }

    private static String describe(Object owner) {
        return owner == null ? "<none>" : owner.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(owner));
    }
}
