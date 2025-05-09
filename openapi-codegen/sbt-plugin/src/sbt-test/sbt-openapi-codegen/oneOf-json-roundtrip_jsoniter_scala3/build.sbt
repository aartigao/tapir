lazy val root = (project in file("."))
  .enablePlugins(OpenapiCodegenPlugin)
  .settings(
    scalaVersion := "3.3.3",
    version := "0.1",
    openapiJsonSerdeLib := "jsoniter",
    openapiXmlSerdeLib := "none",
    openapiStreamingImplementation := "pekko"
  )

val tapirVersion = "1.11.16"
libraryDependencies ++= Seq(
  "com.softwaremill.sttp.tapir" %% "tapir-jsoniter-scala" % tapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % tapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-pekko-http-server" % tapirVersion,
  "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % "0.11.9",
  "com.beachape" %% "enumeratum" % "1.7.6",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.34.1",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.34.1" % "compile-internal",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub-server" % "1.10.0" % Test
)

import sttp.tapir.sbt.OpenapiCodegenPlugin.autoImport.{openapiJsonSerdeLib, openapiUseHeadTagForObjectName}

import scala.io.Source
import scala.util.Using

TaskKey[Unit]("check") := {
  val generatedCode =
    Using(Source.fromFile("target/scala-3.3.3/src_managed/main/sbt-openapi-codegen/TapirGeneratedEndpoints.scala"))(
      _.getLines.mkString("\n")
    ).get
  val expected = Using(Source.fromFile("Expected.scala.txt"))(_.getLines.mkString("\n")).get
  val generatedTrimmed =
    generatedCode.linesIterator.zipWithIndex.filterNot(_._1.isBlank).map { case (a, i) => a.trim -> i }.toSeq
  val expectedTrimmed = expected.linesIterator.filterNot(_.isBlank).map(_.trim).toSeq
  generatedTrimmed.zip(expectedTrimmed).foreach { case ((a, i), b) =>
    if (a != b) sys.error(s"Generated code did not match (expected '$b' on line $i, found '$a')")
  }
  if (generatedTrimmed.size != expectedTrimmed.size)
    sys.error(s"expected ${expectedTrimmed.size} non-empty lines, found ${generatedTrimmed.size}")
  ()
}
