package com.github.huntc.landlord

import scala.collection.immutable.Seq

sealed trait ExecutionMode

case class ClassExecutionMode(`class`: String, args: Seq[String]) extends ExecutionMode

case class JavaArgs(
    cp: Seq[String],
    errors: Seq[String],
    mode: ExecutionMode,
    props: Seq[(String, String)])

/**
 * Parses arguments in a similar manner to the JRE's `java` command. Due to some
 * strange argument conventions, this is hand-rolled. For instance, supporting
 * the `-Dname=value` syntax is not possible with scopt.
 */
object JavaArgs {
  def parse(args: Seq[String]): Either[Seq[String], JavaArgs] = {
    @annotation.tailrec
    def step(as: Seq[String], accum: JavaArgs): JavaArgs =
      as.headOption match {
        case Some(entry) if !entry.startsWith("-") =>
          accum.copy(mode = ClassExecutionMode(entry, as.tail))

        case Some(flag) if flag == "-cp" || flag == "-classpath" =>
          step(
            if (as.tail.isEmpty) Seq.empty else as.tail.tail,
            as.tail.headOption.fold(accum.copy(errors = accum.errors :+ s"$flag requires class path specification")) { cp =>
              accum.copy(cp = classPathStrings(cp))
            }
          )

        case Some(flag) if flag.startsWith("-D") =>
          val parts =
            flag
              .drop(2)
              .split("=", 2)

          step(
            as.tail,
            if (parts.length == 2)
              accum.copy(props = accum.props :+ (parts(0) -> parts(1)))
            else
              accum
          )

        case Some(flag) =>
          step(
            as.tail,
            accum.copy(errors = accum.errors :+ s"Unrecognized option: $flag")
          )

        case None =>
          accum
      }

    val parsed =
      step(args, JavaArgs(Seq.empty, Seq.empty, ClassExecutionMode("", Seq.empty), Seq.empty))

    if (parsed.errors.isEmpty)
      Right(parsed)
    else
      Left(parsed.errors)
  }
}
