# Changelog

## [6.0.0](https://github.com/OneLiteFeatherNET/LuckPerms/compare/v5.6.0...v6.0.0) (2026-08-04)


### ⚠ BREAKING CHANGES

* LPMinestomBootstrap no longer requires a JarInJarClassLoader. Under any other class loader it now falls back to a no-op ClassPathAppender instead of throwing. That fallback is only correct while LuckPerms does not have to download anything - AbstractLuckPermsPlugin hands downloaded dependencies exclusively to getClassPathAppender(), so "no-op appender + download mode" silently discards them. A future builder must reject that combination.

### Features

* two delivery routes for LuckPerms on Minestom, plus a working baseline ([#4](https://github.com/OneLiteFeatherNET/LuckPerms/issues/4)) ([630e14d](https://github.com/OneLiteFeatherNET/LuckPerms/commit/630e14d0dadc754ec85f7037ceb99ea5f0fbbb79))

## Changelog
