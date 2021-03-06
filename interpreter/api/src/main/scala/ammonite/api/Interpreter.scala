package ammonite.api

sealed trait CodeItem

object CodeItem {
  case class Definition(definitionLabel: String, name: String) extends CodeItem
  case class Identity(ident: String) extends CodeItem
  case class LazyIdentity(ident: String) extends CodeItem
  case class Import(imported: String) extends CodeItem
}

case class ParsedCode(code: String, items: Seq[CodeItem], referencedNames: Seq[String])


sealed trait InterpreterError {
  def msg: String
}
object InterpreterError {
  case class ParseError(msg0: Option[String]) extends InterpreterError {
    def msg = msg0.mkString
  }
  case class UnexpectedError(ex: Throwable) extends InterpreterError {
    def msg = s"Unexpected error: $ex\n${ex.getStackTrace.map("  " + _).mkString("\n")}"
  }
  case class PreprocessingError(msg: String) extends InterpreterError
  case class CompilationError(msg: String) extends InterpreterError
  case object Exit extends InterpreterError {
    def msg = "Exiting"
  }
  case object Interrupted extends InterpreterError {
    def msg = "Interrupted"
  }
  case class UserException(ex: Exception, stopClass: String) extends InterpreterError {
    def msg = {

      def printEx(ex: Throwable): Stream[String] =
        if (ex == null)
          Stream.empty
        else {
          val msg =
            ex.toString +
              Option(ex.getMessage)
                .fold("")(" (" + _ + ")") +
              "\n" +
            ex.getStackTrace.takeWhile(_.getClassName != stopClass)
              .map("  " + _)
              .mkString("\n")

          msg #:: printEx(ex.getCause)
        }

      printEx(ex)
        .mkString("\n")
    }
  }
}

/** API for an interpreter */
trait Interpreter {
  /** Initialization parameters */

  def imports: Imports
  def classpath: Classpath

  def getCurrentLine: String
  def sources: Map[String, String]

  def compilerOptions: Seq[String]

  def filterImports: Boolean
  def filterImports_=(value: Boolean): Unit

  def stop(): Unit
  def onStop(action: => Unit): Unit

  def complete(snippetIndex: Int, snippet: String, previousImports: String = null): (Int, Seq[String], Seq[String])
}
