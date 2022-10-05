val scala3Version = "3.2.0"

Global / onChangedBuildSource := ReloadOnSourceChanges
lazy val commonSettings = Seq(
  version := "0.1.0",
  scalaVersion := scala3Version,
  scalacOptions ++= Seq("-Ykind-projector", "-feature")
)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "unjust-core",
    libraryDependencies ++= Seq(
      "io.higherkindness" %% "droste-core" % "0.9.0",
      "org.scalameta" %% "munit" % "0.7.29" % Test
    )
  )

lazy val parser = project
  .in(file("parser"))
  .settings(commonSettings: _*)
  .settings(
    name := "unjust-parser",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-parse" % "0.3.8"
    )
  )
  .dependsOn(core)

lazy val unjust = project
  .in(file("unjust"))
  .settings(commonSettings: _*)
  .settings(
    name := "unjust",
    libraryDependencies ++= Seq(
      "dev.optics" %% "monocle-macro" % "3.1.0",
      "co.fs2" %% "fs2-io" % "3.3.0",
      "com.monovore" %% "decline-effect" % "2.3.1",
      ("io.github.uuverifiers" % "princess" % "2022-07-01")
        .cross(CrossVersion.for3Use2_13),
      ("com.regblanc" %% "scala-smtlib" % "0.2.1-42-gc68dbaa")
        .cross(CrossVersion.for3Use2_13)
    ),
    Compile / mainClass := Some("unjust.Main"),
    assembly / assemblyJarName := "unjust.jar",
    assembly / assemblyMergeStrategy := {
      case "META-INF/MANIFEST.MF" =>
        MergeStrategy.discard
      case x =>
        val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
  .dependsOn(core, parser)

lazy val `unjust-root` = project
  .in(file("."))
  .aggregate(unjust, parser, core)
