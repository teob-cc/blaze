// Fork-only build settings (teob-cc), kept out of build.sbt so upstream syncs don't conflict.

// Like the cc.teob Cats Effect fork, publish for Scala 2.13 and 3 only. Filtering upstream's list
// (instead of hardcoding it) keeps later Scala bumps from build.sbt. teob.sbt loads after build.sbt.
ThisBuild / crossScalaVersions ~= (_.filterNot(_.startsWith("2.12.")))
