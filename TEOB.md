# cc.teob fork of http4s blaze

This is [http4s/blaze](https://github.com/http4s/blaze) with a few fixes, published as `cc.teob`
for wider testing. Artifact names, packages and binary API are the same as upstream: MiMa checks
every release against the `org.http4s` releases, so it is a drop-in replacement.

The fork's `series/0.23` is upstream `series/0.23` plus the fork's commits; work and releases
happen directly on it.

It goes together with the [cc.teob Cats Effect fork](https://github.com/teob-cc/cats-effect),
but does not depend on it: blaze still depends on the upstream `org.http4s` and `org.typelevel`
libraries, and swapping in `cc.teob` Cats Effect is up to the application.

## Changes

- **Non-blocking websocket upgrade.** `WebSocketSupport` no longer calls
  `Dispatcher.unsafeRunSync` from the callback that completes the upgrade. It blocked a thread of
  the server's execution context, by default a Cats Effect compute worker, which then had to be
  handed off as a blocker thread, or, on a small or fixed compute pool, deadlocked the upgrade.

## Using it

Replace the upstream modules, and exclude them so that nothing brings `org.http4s` blaze back in
transitively:

```scala
val blazeTeob = "0.23.19-teob.1"

libraryDependencies ++= Seq(
  "cc.teob" %% "http4s-blaze-server" % blazeTeob
)

excludeDependencies ++= Seq(
  "org.http4s" %% "http4s-blaze-server",
  "org.http4s" %% "http4s-blaze-core",
  "org.http4s" %% "blaze-http",
  "org.http4s" %% "blaze-core"
)
```

`cc.teob` `http4s-blaze-server` brings `cc.teob` `http4s-blaze-core`, `blaze-http` and
`blaze-core` with it. Use `http4s-blaze-client` (and exclude `org.http4s` `http4s-blaze-client`)
the same way.

## Releasing

Run the **Release cc.teob to Sonatype** workflow from the Actions tab with a version such as
`0.23.19-teob.1`. It runs the tests, publishes every module for Scala 2.13 and 3 to Sonatype
Central, and tags the commit `teob-v<version>`. Tick `dry_run` to build, sign and stage everything
without uploading or tagging.

Unlike upstream, the fork does not publish for Scala 2.12 (set in `teob.sbt`), like the Cats
Effect fork. Like upstream, it builds and publishes on JDK 8.

It uses the `teob-cc` organisation secrets `SONATYPE_USER`, `SONATYPE_PASSWORD`, `GPG_SECRET`
and `GPG_PASS`, described in the Cats Effect fork's
[TEOB.md](https://github.com/teob-cc/cats-effect/blob/series/3.x/TEOB.md#releasing).

## Syncing with upstream

Use **Sync fork** on GitHub, or locally:

```sh
git pull upstream series/0.23   # merge upstream into the fork's series/0.23
git push
```

All fork-specific build changes are in `project/TeobFork.scala`, `teob.sbt`,
`.github/workflows/teob-release.yml` and this file, so `build.sbt` does not conflict. Upstream's
**Continuous Integration** workflow is disabled on the fork.
