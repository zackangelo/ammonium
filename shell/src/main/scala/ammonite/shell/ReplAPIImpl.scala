package ammonite.shell

import java.io.File

import ammonite.shell.IvyConstructor.Resolvers
import org.apache.ivy.plugins.resolver.DependencyResolver
import com.github.alexarchambault.ivylight.{ResolverHelpers, IvyHelper}

import scala.reflect.runtime.universe._
import acyclic.file

import ammonite.interpreter.{ Classes => _, _ }
import ammonite.shell.util._


class ReplAPIImpl(
  intp: Interpreter,
  startJars: Seq[File],
  startIvys: Seq[(String, String, String)],
  startResolvers: Seq[DependencyResolver]
) extends ReplAPI {
  def exit = throw Exit
  def help = "Hello!"

  def typeOf[T: WeakTypeTag] = scala.reflect.runtime.universe.weakTypeOf[T]
  def typeOf[T: WeakTypeTag](t: => T) = scala.reflect.runtime.universe.weakTypeOf[T]

  object load extends Load{

    def apply(line: String) = {
      val res = intp(
        line,
        (_, _) => (), // Discard history of load-ed lines,
        print
      )

      res match {
        case Res.Failure(msg) => println(Console.RED + msg + Console.RESET)
        case _ =>
      }

      intp.handleOutput(res)
    }

    private var userJars = startJars
    private var userIvys = startIvys
    private var warnedJars = Set.empty[File]
    private var userResolvers = startResolvers

    def jar(jar: File*): Unit = {
      userJars = userJars ++ jar
      intp.classes.addJars(jar: _*)
      intp.init()
    }
    def ivy(coordinates: (String, String, String)*): Unit = {
      userIvys = userIvys ++ coordinates
      val newIvyJars = IvyHelper.resolve(userIvys, userResolvers)
      val newJars = newIvyJars ++ userJars

      val removedJars = intp.classes.jars.toSet -- newJars
      if ((removedJars -- warnedJars).nonEmpty) {
        println(
          s"Warning: the following JARs were previously added and are no more required:" +
          (removedJars -- warnedJars).toList.sorted.map("  ".+).mkString("\n", "\n", "\n") +
          "It is likely they were updated, which may lead to instabilities in the REPL.")
      }
      warnedJars = removedJars

      intp.classes.addJars(newJars: _*)
      intp.init()
    }
    def resolver(resolver: Resolver*): Unit = {
      userResolvers = userResolvers ++ resolver.map {
        case Resolvers.Local =>
          ResolverHelpers.localRepo
        case Resolvers.Central =>
          ResolverHelpers.defaultMaven
      }
    }
  }
  lazy val power: Power = new Power with Serializable {
    val classes = new Classes with Serializable {
      def currentClassLoader = intp.classes.currentClassLoader
      def dirs = intp.classes.dirs
      def addJar(jar: File) = intp.classes.addJars(jar)
      def jars = intp.classes.jars
      def onJarsAdded(action: Seq[File] => Unit) = intp.classes.onJarsAdded(action)
      def fromAddedClasses(name: String) = intp.classes.fromAddedClasses(name)
    }

    var onStopHooks = Seq.empty[() => Unit]
    def onStop(action: => Unit) = onStopHooks = onStopHooks :+ { () => action }
    def stop() = onStopHooks.foreach(_())

    def complete(snippetIndex: Int, snippet: String) =
      intp.pressy.complete(snippetIndex, intp.imports.previousImportBlock, snippet)

    def getShow = intp.getShow
    def setShow(v: Boolean) = intp.setShow(v)

    def newCompiler() = intp.init()
    def imports = intp.imports.previousImportBlock
  }
  def history = intp.history.toVector.dropRight(1)
}

// From Ammonite's IvyThing

trait ShellReplAPIImpl extends FullShellReplAPI {
  def colors: ColorSet
  def shellPrompt0: Ref[String]


  def clear = ()

  def shellPrompt: String = shellPrompt0()
  def shellPrompt_=(s: String) = shellPrompt0() = s

  def shellPPrint[T: WeakTypeTag](value: => T, ident: String) = {
    colors.ident + ident + colors.reset + ": " +
      colors.`type` + weakTypeOf[T].toString + colors.reset
  }
  def shellPrintDef(definitionLabel: String, ident: String) = {
    s"defined ${colors.`type`}$definitionLabel ${colors.ident}$ident${colors.reset}"
  }
  def shellPrintImport(imported: String) = {
    s"${colors.`type`}import ${colors.ident}$imported${colors.reset}"
  }
}
