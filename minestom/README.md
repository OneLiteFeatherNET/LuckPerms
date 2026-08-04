# LuckPerms for Minestom

Minestom has no plugin folder, no `ops.json` and no string permission system. LuckPerms therefore
cannot be "dropped in" — the host application starts it from its own `main()` and owns its lifecycle.
This document is for a team embedding LuckPerms into a Minestom server.

> **Version.** The API described here is the one introduced with **6.0.0**. It is a hard break with
> everything that came before: there is no deprecation window and no compatibility shim (see
> [Migrating from the old API](#migrating-from-the-old-api)). `build.gradle` currently declares
> `5.6.0` — that is the Release Please baseline; the first release carrying these changes is `6.0.0`.
> Check the repository for the newest published version before pinning one.

## Contents

- [Which artifact](#which-artifact)
- [Route A — `minestom-loader` (JarInJar)](#route-a--minestom-loader-jarinjar)
- [Route B — `minestom-library` (flat, shaded by you)](#route-b--minestom-library-flat-shaded-by-you)
- [Migrating from the old API](#migrating-from-the-old-api)
- [Permission checks](#permission-checks)
- [Operating it](#operating-it)
- [Known limits](#known-limits)
- [Options reference](#options-reference)

## Which artifact

There are two equally supported delivery routes over the same platform core. They differ only in
packaging and in how runtime dependencies are obtained — never in behaviour.

| Artifact | Packaging | `DependencyMode` | For |
|---|---|---|---|
| `net.luckperms:minestom-loader` | JarInJar, ~3.9 MiB | `DOWNLOAD` (default) | a standalone server start; an extension |
| `net.luckperms:minestom-library` | flat, ~40 MiB | `PRELOADED` (enforced) | a consumer that shades us into its own fat jar via ShadowJar |

**The decision rule:** if your server jar is produced by ShadowJar and LuckPerms has to end up
*inside* it, take `minestom-library` — a JarInJar cannot survive being shaded, because Shadow
rewrites the bootstrap class name while the nested jar stays an opaque blob. In every other case take
`minestom-loader`: it is byte-for-byte the packaging upstream LuckPerms uses on ten of its twelve
platforms, it is a tenth of the size, and it is the only route that can download dependencies at
runtime.

Both routes hand out the same `LuckPermsMinestomHandle`, so host code written against that interface
compiles and runs on either.

## Route A — `minestom-loader` (JarInJar)

### `build.gradle.kts`

```kotlin
plugins {
    java
    application
}

java {
    // The platform is compiled with --release 25.
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    mavenCentral()
    maven("https://repo.onelitefeather.dev/onelitefeather-releases")
}

dependencies {
    implementation("net.minestom:minestom:2026.07.22-26.2")

    // One self-contained jar. Its POM declares no dependencies at all, so there
    // is nothing to exclude - no Adventure clash, no gson clash, no stale Guava.
    implementation("net.luckperms:minestom-loader:6.0.0")
}
```

The jar carries, flat and visible to your code: `net.luckperms.api.*`,
`me.lucko.luckperms.minestom.app.*` and `me.lucko.luckperms.minestom.loader.MinestomLoader`.
Everything else — the platform implementation and its libraries — lives in the nested
`luckperms-minestom.jarinjar` behind its own class loader.

### `main()`

```java
import me.lucko.luckperms.minestom.app.LuckPermsCommandConditions;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomHandle;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomOptions;
import me.lucko.luckperms.minestom.loader.MinestomLoader;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public final class ServerMain {

    public static void main(String[] args) {
        // LuckPerms needs the console sender, the command manager and the event
        // tree, so MinecraftServer.init() has to run first. Enabling it earlier
        // fails with an explicit IllegalStateException.
        MinecraftServer server = MinecraftServer.init();

        LuckPermsMinestomHandle luckPerms = MinestomLoader.create(
                LuckPermsMinestomOptions.builder()
                        .dataDirectory(Path.of("run", "luckperms"))
                        .logger(LoggerFactory.getLogger("myserver.permissions"))
                        .commandAliases(List.of("luckperms", "lp"))
                        .build());

        // load() reads the config and resolves dependencies; enable() opens
        // storage, registers listeners and commands and publishes the API.
        // Both block. enable() calls load() itself if you skip it.
        luckPerms.load().enable();

        // The supported shutdown path. Without it, pending writes are lost when
        // the process ends. There is no JVM shutdown hook unless you ask for one.
        MinecraftServer.getSchedulerManager().buildShutdownTask(luckPerms::close);

        Command gamemode = new Command("gm");
        gamemode.setCondition(LuckPermsCommandConditions.permission("myserver.command.gamemode"));
        MinecraftServer.getCommandManager().register(gamemode);

        LuckPerms api = LuckPermsProvider.get();

        server.start("0.0.0.0", 25565);
    }
}
```

`MinestomLoader.create()` without arguments gives you fully default options.

## Route B — `minestom-library` (flat, shaded by you)

### `build.gradle.kts`

```kotlin
plugins {
    java
    application
    id("com.gradleup.shadow") version "9.0.0"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    mavenCentral()
    maven("https://repo.onelitefeather.dev/onelitefeather-releases")
}

dependencies {
    implementation("net.minestom:minestom:2026.07.22-26.2")

    // ~40 MiB, every storage backend and messaging service already shaded and
    // relocated under me.lucko.luckperms.lib.*. Its POM declares no dependencies,
    // so no excludes are needed - Adventure, gson and slf4j are deliberately NOT
    // in the jar and come from Minestom.
    implementation("net.luckperms:minestom-library:6.0.0")
}

tasks.shadowJar {
    // JDBC drivers and friends are registered through META-INF/services.
    mergeServiceFiles()

    // Do NOT relocate net.luckperms.api if anything else in the process has to
    // see the same API classes (a CloudNet bridge, a sidecar extension).
    // Relocating me.lucko.luckperms is unnecessary - the internal libraries are
    // already under me.lucko.luckperms.lib.* - and is untested.
}
```

> **Resource collision.** `minestom-library` ships LuckPerms' `config.yml` template at the **root**
> of the jar, because that is where `getResourceStream("config.yml")` looks for it. If your own
> project also has a root-level `config.yml`, ShadowJar will pick one of them and drop the other.
> Move yours into a subdirectory, or accept that LuckPerms writes your file into its data directory
> on first boot.

Shading is the intended use, but a plain classpath entry works too — for example in tests — as long
as the dependency mode stays `PRELOADED`.

### `main()`

```java
import me.lucko.luckperms.minestom.LuckPermsMinestom;
import me.lucko.luckperms.minestom.app.DependencyMode;
import me.lucko.luckperms.minestom.app.LuckPermsCommandConditions;
import me.lucko.luckperms.minestom.app.LuckPermsMinestomHandle;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public final class ServerMain {

    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();

        LuckPermsMinestomHandle luckPerms = LuckPermsMinestom.create(
                LuckPermsMinestom.builder()               // == LuckPermsMinestomOptions.builder()
                        .dataDirectory(Path.of("run", "luckperms"))
                        .dependencyMode(DependencyMode.PRELOADED)   // mandatory on this route
                        .logger(LoggerFactory.getLogger("myserver.permissions"))
                        .commandAliases(List.of("luckperms", "lp"))
                        .build());

        luckPerms.load().enable();

        MinecraftServer.getSchedulerManager().buildShutdownTask(luckPerms::close);

        Command gamemode = new Command("gm");
        gamemode.setCondition(LuckPermsCommandConditions.permission("myserver.command.gamemode"));
        MinecraftServer.getCommandManager().register(gamemode);

        LuckPerms api = LuckPermsProvider.get();

        server.start("0.0.0.0", 25565);
    }
}
```

`DependencyMode.PRELOADED` is not optional here. In a flat jar LuckPerms runs on the application
class loader, which since Java 9 is not a `URLClassLoader`, so there is nothing to append downloaded
jars to. `create()` rejects `DOWNLOAD` and `JAR_IN_JAR` immediately with an explanation rather than
letting you find out later through a `NoClassDefFoundError` that points nowhere near the cause.

## Migrating from the old API

The old bootstrap chain no longer exists and no longer compiles:

```java
// BEFORE (5.x) - gone
MinestomLoader.get().load().registerShutdownHook().start();
```

```java
// AFTER (6.0.0) - literal replacement, same behaviour as before
LuckPermsMinestomHandle luckPerms = MinestomLoader.create(
        LuckPermsMinestomOptions.builder()
                .dataDirectory(Path.of("data"))   // the old hard-coded default
                .commandAliases(List.of("luckperms", "lp", "perm", "perms",
                        "permission", "permissions"))   // the old six aliases
                .registerShutdownHook(true)             // the old default
                .build())
        .load()
        .enable();
```

If you do not need bug-for-bug compatibility, prefer the shorter form and wire `close()` into the
Minestom lifecycle instead of using a JVM hook:

```java
LuckPermsMinestomHandle luckPerms = MinestomLoader.create(
        LuckPermsMinestomOptions.builder()
                .dataDirectory(Path.of("run", "luckperms"))
                .build())
        .load()
        .enable();

MinecraftServer.getSchedulerManager().buildShutdownTask(luckPerms::close);
```

### Every break, one by one

These are taken from the `BREAKING CHANGE:` footers of the commits that introduced them.

| # | What changed | Old | New | What to do |
|---|---|---|---|---|
| 1 | **Bootstrap call chain.** `MinestomLoader.get()` is gone; there is no process-wide singleton. `registerShutdownHook()` and `start()` as *methods* are gone. | `get().load().registerShutdownHook().start()` | `create(options).load().enable()` | Rewrite the call. `start()` → `enable()`; the shutdown hook is an option, not a call. |
| 2 | **Data directory default.** Still `data`, but it is now overridable, and a `luckperms.data-dir` system property or `LUCKPERMS_DATA_DIR` environment variable makes LuckPerms write somewhere else **without any code change**. | always `./data` | builder > `luckperms.data-dir` > `LUCKPERMS_DATA_DIR` > `./data` | If you rely on `./data`, set `.dataDirectory(Path.of("data"))` explicitly, or make sure neither override is set in your deployment. |
| 3 | **Shutdown hook: on → off.** This is the dangerous one: it changes silently, without a compiler error. A server that does neither loses pending writes when the process ends. | JVM hook always registered | `registerShutdownHook` defaults to `false` | Call `handle.close()` from your lifecycle — `MinecraftServer.getSchedulerManager().buildShutdownTask(handle::close)` — or set `.registerShutdownHook(true)`. A startup log line tells you which of the two you got. |
| 4 | **Command aliases: six → two.** Minestom's `CommandManager#register` throws on a name clash, so every extra alias was another way for `enable()` to abort on a host that happens to use that name. | `luckperms`, `lp`, `perm`, `perms`, `permission`, `permissions` | `luckperms`, `lp` | If you relied on `/perms` or `/permissions`, list them explicitly via `.commandAliases(...)`. An empty list disables command registration entirely. |
| 5 | **`LuckPermsCommandConditions` moved.** | `me.lucko.luckperms.minestom.LuckPermsCommandConditions`, shipped in `net.luckperms:minestom` | `me.lucko.luckperms.minestom.app.LuckPermsCommandConditions`, shipped in `net.luckperms:minestom-app` (and contained in both delivery artifacts) | Change the import. Nothing else. |
| 6 | **`anyLuckPermsCommand()` is gone** from the public surface. It needed `CommandManager#hasPermissionForAny`, which has no representation in the public API. | `LuckPermsCommandConditions.anyLuckPermsCommand()` | — | Nothing to do: `/luckperms` builds that condition internally now. If you used it on your own command, use `permission("luckperms.<something>")` instead. |
| 7 | **`MinestomCommandExecutor`'s constructor** takes the alias list as a second argument. | `new MinestomCommandExecutor(plugin)` | `new MinestomCommandExecutor(plugin, aliases)` | Internal class — only relevant if you were reaching into it. |
| 8 | **A second instance in one process now throws** instead of silently replacing the first one's API provider. | second instance won silently | `IllegalStateException` from `create()` | Close the existing handle before creating another. Relevant mostly in tests. |
| 9 | **Player permission checks return real results.** Previously every player check was constantly `FALSE`; only the console got through. `/luckperms` is now also hidden from, and rejected for, senders without any LuckPerms permission — they used to reach the command and get a "no permission" message. | always `FALSE` | answered by LuckPerms | Nothing to do; this is the fix. Expect players to suddenly *have* the permissions your database says they have. |

Two more things worth knowing while migrating:

- **`LuckPermsMinestomHandle` extends `AutoCloseable`.** `close()` is real now: commands are
  unregistered, the event node is detached, storage and the scheduler are closed, the API provider is
  deregistered, and on the loader route the nested class loader is closed. It is idempotent and never
  throws for a handle that was never enabled.
- **The published coordinates move from a snapshot to a release repository.** `5.6-SNAPSHOT`
  consumers must switch to `https://repo.onelitefeather.dev/onelitefeather-releases`.

## Permission checks

Minestom has no string permission system, so `Command#setCondition(CommandCondition)` is the only
place a host can hook permission checks into its own commands. `LuckPermsCommandConditions` is that
hook, and it lives in the contract module, which means you can reference it directly on both routes.

```java
import me.lucko.luckperms.minestom.app.LuckPermsCommandConditions;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;

Command fly = new Command("fly");
fly.setCondition(LuckPermsCommandConditions.permission("myserver.command.fly"));
fly.setDefaultExecutor((sender, context) -> sender.sendMessage("flying"));
MinecraftServer.getCommandManager().register(fly);
```

Minestom evaluates a condition both when executing the command and when computing tab-complete
visibility on connect, so a single condition covers both.

**Conditions may be created before LuckPerms is enabled.** They resolve LuckPerms lazily on every
invocation through `LuckPermsProvider`, so you can build and register your whole command tree during
startup and enable LuckPerms afterwards. While LuckPerms is not up the condition falls back to
"console only": the console passes, everyone else is denied. It never throws.

### A direct check

```java
import me.lucko.luckperms.minestom.app.LuckPermsCommandConditions;
import net.minestom.server.command.CommandSender;

boolean canBuild = LuckPermsCommandConditions.hasPermission(sender, "myserver.build");

// "Is LuckPerms up at all?" - useful to distinguish "denied" from "not running".
boolean up = LuckPermsCommandConditions.isAvailable();
```

Or straight through the API, if you already hold a `Player`:

```java
LuckPerms api = LuckPermsProvider.get();
User user = api.getUserManager().getUser(player.getUuid());
if (user == null) {
    return false;                       // not loaded - see below
}
ContextManager contexts = api.getContextManager();
QueryOptions options = contexts.getQueryOptions(user).orElseGet(contexts::getStaticQueryOptions);
boolean allowed = user.getCachedData()
        .getPermissionData(options)
        .checkPermission("myserver.build")
        .asBoolean();
```

### The "user not loaded yet" case

A check for a player whose data has not been loaded **returns `false`** and logs a `WARN` line:

```
Permission check for 'myserver.build' by Steve (7f4c...) could not be answered: the user is not loaded. Denying.
```

This is deliberately not silent, because from the outside "not loaded" and "no permission" look
identical. If you see that line, the check ran too early — LuckPerms loads user data in
`AsyncPlayerPreLoginEvent` and denies the login outright if it is still missing at the configuration
stage, so in normal operation a player who is in the world always has data.

## Operating it

### Data directory

Everything LuckPerms writes lives under one directory: `config.yml`, the storage files (H2 by
default), `translations/`, and on the loader route `libs/`.

Resolution order, highest first:

| Source | Value |
|---|---|
| `.dataDirectory(Path)` on the builder | whatever you pass; relative paths resolve against the working directory |
| System property | `-Dluckperms.data-dir=/srv/mc/luckperms` |
| Environment variable | `LUCKPERMS_DATA_DIR=/srv/mc/luckperms` |
| Default | `./data` |

The builder wins on purpose: it is a decision made in code. The property and the variable exist so an
ops team can move the directory without rebuilding the host. Blank values are ignored, and the result
is always absolute and normalised.

`options.dataDirectoryOrigin()` tells you which of the four it was — `"builder"`,
`"system property luckperms.data-dir"`, `"environment variable LUCKPERMS_DATA_DIR"` or `"default"`.
That string is also in the startup line below, so "LuckPerms wrote its config somewhere I did not
expect" can be told apart from "my setting was ignored".

### First boot

- **`DOWNLOAD` (loader route)** fetches LuckPerms' runtime dependencies from the LuckPerms Maven
  mirror, falling back to Maven Central, into `<dataDirectory>/libs`, and appends them to the nested
  class loader. **This needs network access on the first boot** of a fresh data directory. Later
  boots reuse the cache. If your deployment has no outbound network, pre-seed `libs/` or use the
  library artifact.
- **`PRELOADED` (library route)** downloads nothing — everything is in the jar.
- **Translations** are a separate thing and affect *both* routes: `auto-install-translations` in
  `config.yml` defaults to `true`, and LuckPerms then refreshes `<dataDirectory>/translations`
  asynchronously after startup. Failures are swallowed; the built-in English strings keep working.
  Set the key to `false` for an air-gapped deployment.
- `enable()` **blocks** — it connects to storage and, in `DOWNLOAD` mode, downloads jars — and it
  runs *before* your `server.start(...)`. Under an orchestrator with a readiness timeout, budget for
  it. The exact cost has not been measured.

### The startup diagnostic line

`enable()` logs one line naming everything a support request would otherwise have to guess at:

```
Minestom integration ready | data directory: /srv/mc/run/luckperms (from builder) | dependencies: DOWNLOAD | permission bridge: active (player checks answered by LuckPerms itself) | commands: luckperms, lp | event node: luckperms | shutdown hook: off
```

How to read it:

| Field | What it tells you |
|---|---|
| `data directory` … `(from …)` | Where config and storage really are, and *why* that path was chosen. If you edited a `config.yml` and nothing changed, this is the first place to look. |
| `dependencies` | `DOWNLOAD`, `JAR_IN_JAR` or `PRELOADED`. Decides what a startup failure looks like: `DOWNLOAD` failures are network or `libs/`, `PRELOADED` failures are a missing shaded class. |
| `permission bridge` | `active` means player checks are answered by LuckPerms. `INACTIVE` means every player check will be wrong — that is a bug report. |
| `commands` | The names actually registered, or `<none registered>` if you passed an empty alias list. If `/lp` does not exist, this line already said so. |
| `event node` | `luckperms` if the listener node is attached, `NOT ATTACHED` otherwise. |
| `shutdown hook` | `registered` or `off`. |

If the hook is off, a second line follows reminding you to wire `close()` into your lifecycle. That
line exists precisely because break #3 above has no compiler error.

`/lp verbose` is the normal tool for "permissions do not apply", but it requires the very permission
that is usually missing — which is why this line exists.

## Known limits

Honest list. None of these are bugs; all of them generate bug reports.

### The first admin has to be granted from the console

Minestom has no `ops.json` and no string permissions, and LuckPerms adds no implicit superuser — a
high `permissionLevel` grants nothing. On a fresh server with an empty database, **nobody except the
console can use `/lp`.** The first grant therefore runs from the console:

```
lp user <name> parent add admin
lp group admin permission set luckperms.* true
```

or by writing to the storage backend directly. This is a deliberate decision, not an oversight.

### `minestom-library` specific

- **No H2 1.x migration.** `MigrateH2ToVersion2` loads the legacy H2 driver through a second isolated
  class loader; both versions live in the unrelocated `org.h2` package, so only one of them fits into
  a flat jar, and that has to be the modern one. **If you carry a LuckPerms H2 file written by
  LuckPerms < 5.4, migrate it once with the loader artifact before switching to the library.**
- **No `StorageType.REST`.** The REST client has no `Dependency` enum constant at all, so the
  dependency manager cannot fetch it either — it is missing from every upstream platform jar as well.
  Bundling it here would make this artifact the only one where REST works.
- **No end-to-end smoke test yet.** The flat route is verified statically (relocation consistency,
  class loading and linking, presence of every required class) and up to `create()`, but as of this
  writing no test has booted a real Minestom server from the published 40 MiB artifact. The loader
  route is covered by an end-to-end suite. Treat the library route as the less-travelled one.

### Both routes

- **`getServerVersion()` reports a compile-time constant.** It returns Minestom's `VERSION_NAME`,
  which is baked into the Minestom jar LuckPerms was compiled against — not the version actually
  running. Do not use it to gate behaviour.
- **An alias collision aborts `enable()`.** Minestom's `CommandManager#register` throws an
  `IllegalStateException` when a name is already taken, and that propagates out of `enable()`. Two
  consequences: register your own commands *after* LuckPerms if a clash is plausible, or rename the
  alias via `.commandAliases(...)`. And note that commands are registered late in `enable()`, after
  listeners and storage — a handle whose `enable()` threw is in a partially started state. `close()`
  still releases the single-instance slot, but it does not run the disable path; restart the process.
- **One instance per process.** `LuckPermsProvider` holds the running API in a static field, so a
  second instance would silently replace the first one's provider. `create()` throws instead. If you
  restart LuckPerms inside a running JVM (tests), `close()` the old handle first.
- **Guava, gson, slf4j and Adventure are not relocated** in either artifact, on purpose — LuckPerms'
  own `Dependency.java` has no relocation rule for them, and its runtime-downloaded dependencies
  reference them unrelocated. gson in particular comes from Minestom while `common` compiles against
  an older version; formally binary-compatible, but not exhaustively tested.

## Options reference

`LuckPermsMinestomOptions.builder()` — every setter returns the builder; `build()` is immutable.

| Option | Default | Notes |
|---|---|---|
| `dataDirectory(Path)` | see [Data directory](#data-directory) | Pass `null` to fall back to the property/env/default chain. |
| `logger(Logger)` | `LoggerFactory.getLogger("luckperms")` | An slf4j logger. |
| `commandAliases(List<String>)` | `["luckperms", "lp"]` | Primary name first, rest are aliases. Duplicates are dropped, order preserved; blank entries throw. An empty list skips command registration. |
| `registerShutdownHook(boolean)` | `false` | Prefer `buildShutdownTask(handle::close)`; a JVM hook's ordering relative to the host's own shutdown is undefined. |
| `dependencyMode(DependencyMode)` | `DOWNLOAD` | `DOWNLOAD`, `JAR_IN_JAR` or `PRELOADED`. The first two need a `JarInJarClassLoader`; `create()` rejects them otherwise. |
| `eventNode(EventNode<Event>)` | global event handler | LuckPerms always attaches its own child node named `luckperms`, so `close()` can detach every listener at once. |

Constants on `LuckPermsMinestomOptions`: `DATA_DIRECTORY_PROPERTY` (`luckperms.data-dir`),
`DATA_DIRECTORY_ENV` (`LUCKPERMS_DATA_DIR`), `DEFAULT_DATA_DIRECTORY` (`data`),
`DEFAULT_COMMAND_ALIASES`.

`LuckPermsMinestomHandle`: `options()`, `load()`, `enable()`, `isEnabled()`, `close()`.

## Building from source

```sh
./gradlew :minestom:loader:shadowJar     # minestom/loader/build/libs/LuckPerms-Minestom-<version>.jar
./gradlew :minestom:library:shadowJar    # minestom/library/build/libs/minestom-library-<version>.jar
```

Requires a Java 25 toolchain. Module layout:

| Module | Contains |
|---|---|
| `minestom/app` | The contract layer, visible on both sides of the class loader boundary: `LuckPermsMinestomOptions`, `DependencyMode`, `LuckPermsMinestomHandle`, `LuckPermsMinestomInstanceLock`, `LuckPermsCommandConditions`. |
| `minestom/` | The platform implementation, plus `LuckPermsMinestom` — the flat route's entry point. |
| `minestom/loader` | The JarInJar packaging and `MinestomLoader`. |
| `minestom/library` | Pure packaging, no sources: the flat fat jar. |

Design rationale and the reasoning behind each decision above is recorded in
[`docs/minestom-integration-analyse.md`](../docs/minestom-integration-analyse.md) (German).
