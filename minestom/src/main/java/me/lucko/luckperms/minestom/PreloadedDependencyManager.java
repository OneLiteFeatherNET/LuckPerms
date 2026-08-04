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

import me.lucko.luckperms.common.dependencies.Dependency;
import me.lucko.luckperms.common.dependencies.DependencyManager;
import me.lucko.luckperms.common.storage.StorageType;
import me.lucko.luckperms.minestom.app.DependencyMode;

import java.util.Set;

/**
 * A {@link DependencyManager} that loads nothing, because everything is already
 * on the class path.
 *
 * <p>This is what {@link DependencyMode#PRELOADED} uses. It is the mode the flat
 * library packaging is forced into: all libraries are shaded into the artifact
 * at build time, LuckPerms runs on the application class loader, and there is no
 * class loader left that would accept additional URLs.</p>
 *
 * <p>Modelled on {@code TestPluginBootstrap.TestDependencyManager} in the
 * standalone test suite, which solves the same problem for tests.</p>
 */
public class PreloadedDependencyManager implements DependencyManager {

    @Override
    public void loadDependencies(Set<Dependency> dependencies) {
        // Everything is on the class path already.
    }

    @Override
    public void loadStorageDependencies(Set<StorageType> storageTypes, boolean redis, boolean rabbitmq, boolean nats) {
        // Everything is on the class path already.
    }

    @Override
    public ClassLoader obtainClassLoaderWith(Set<Dependency> dependencies) {
        // There is no isolated loader - the dependency is either visible from
        // here or it does not exist at all.
        return getClass().getClassLoader();
    }

    @Override
    public void close() {
        // Nothing owned, nothing to close.
    }
}
