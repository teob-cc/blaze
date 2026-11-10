// Fork-only build settings (teob-cc), kept out of build.sbt so upstream syncs don't conflict.

// Like the cc.teob Cats Effect fork, publish for Scala 2.13 and 3 only. Filtering upstream's list
// (instead of hardcoding it) keeps later Scala bumps from build.sbt. teob.sbt loads after build.sbt.
ThisBuild / crossScalaVersions ~= (_.filterNot(_.startsWith("2.12.")))

// Every CI job runs on teob-cc's own runners, never on GitHub-hosted ones: the "test-public"
// pool (amd64 Linux, JDK 25 image; setup-java still installs the JDK each job asks for).
// sbt-github-actions renders runs-on as [ "${{ matrix.os }}", <extra labels> ], so the matrix
// "os" is the "self-hosted" label. The publish and added jobs (dependency submission, steward
// validation) get the same labels; their default OS would otherwise be ubuntu-22.04.
val TeobRunnerLabels = List("self-hosted", "test-public")
ThisBuild / githubWorkflowOSes := TeobRunnerLabels.take(1)
ThisBuild / githubWorkflowGeneratedCI ~= (_.map { job =>
  job
    .withOses(TeobRunnerLabels.take(1))
    .withRunsOnExtraLabels(TeobRunnerLabels.drop(1))
    .withMatrixExcs(job.matrixExcs.filter(_.matching.get("os").forall(TeobRunnerLabels.contains)))
})
// The generated clean.yml hardcodes runs-on: ubuntu-latest; .github/workflows/clean.yml is kept
// by hand instead, with the same labels.
ThisBuild / githubWorkflowIncludeClean := false
