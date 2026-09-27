/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._, Keys._

import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._
import org.http4s.sbt.Http4sOrgPlugin
import org.typelevel.sbt.TypelevelPlugin

/** Everything that makes the teob-cc fork publish as `cc.teob` lives here, so that `build.sbt`
  * stays identical to upstream and syncing with http4s/blaze does not conflict.
  *
  * Artifacts keep their upstream names (`http4s-blaze-server`, `blaze-core`, ...) and are
  * checked with MiMa against the `org.http4s` releases, so `cc.teob` is a binary-compatible
  * drop-in replacement.
  */
object TeobFork extends AutoPlugin {

  val Organization = "cc.teob"
  val Repo = "https://github.com/teob-cc/blaze"

  // after Http4sOrgPlugin, so these win over the organization it sets
  override def requires = plugins.JvmPlugin && TypelevelPlugin && Http4sOrgPlugin
  override def trigger = allRequirements

  // set by the release workflow; without it the upstream git-derived snapshot version is used
  private val releaseVersion = sys.env.get("TEOB_VERSION").map(_.trim).filter(_.nonEmpty)

  // build.sbt's upstream values are overridden per project below, so sbt would flag them as unused
  override def globalSettings =
    Seq(excludeLintKeys ++= Set[Def.KeyedInitialize[_]](organization, homepage, scmInfo))

  override def buildSettings =
    releaseVersion.toSeq.flatMap(v => Seq(version := v, isSnapshot := v.endsWith("-SNAPSHOT")))

  // project scope, so these win over the `ThisBuild` values in build.sbt
  override def projectSettings =
    Seq(
      organization := Organization,
      homepage := Some(url(Repo)),
      scmInfo := Some(ScmInfo(url(Repo), "git@github.com:teob-cc/blaze.git")),
      mimaPreviousArtifacts := mimaPreviousArtifacts.value.map(_.withOrganization("org.http4s")),
    )
}
