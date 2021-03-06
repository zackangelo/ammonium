package ammonite.interpreter

import scala.tools.nsc._
import scala.tools.nsc.plugins.{PluginComponent, Plugin}
import ammonite.api.Import

/**
 * Used to capture the names in scope after every execution, reporting them
 * to the `output` function. Needs to be a compiler plugin so we can hook in
 * immediately after the `typer`
 */
class AmmonitePlugin(g: scala.tools.nsc.Global, output: Seq[Import] => Unit) extends Plugin{
  val name: String = "AmmonitePlugin"
  val global: Global = g
  val description: String = "Extracts the names in scope for the Ammonite REPL to use"
  val components: List[PluginComponent] = List(
    new PluginComponent {
      val global = g
      val runsAfter = List("typer")
      override val runsRightAfter = Some("typer")
      val phaseName = "AmmonitePhase"

      def newPhase(prev: Phase): Phase = new g.GlobalPhase(prev) {
        def name = phaseName
        def apply(unit: g.CompilationUnit): Unit = AmmonitePlugin(g)(unit, output)
      }
    }
  )
}
object AmmonitePlugin{
  def apply(g: Global)(unit: g.CompilationUnit, output: Seq[Import] => Unit) = {

    def decode(t: g.Tree) = {
      val sym = t.symbol
      (sym.decodedName, sym.decodedName, "", sym.isImplicit)
    }

    val stats = unit.body.children.init.last match {
      case m: g.ModuleDef =>
        def inner(m: g.ModuleDef) =
          m.impl.body.collectFirst{case t: g.ModuleDef if t.name.toString == "$user" => t}
            .getOrElse(m)

        inner(m).impl.body
      case c: g.ClassDef =>
        def inner(c: g.ClassDef) =
          c.impl.body.collectFirst{case t: g.ClassDef => t}
            .getOrElse(throw new IllegalArgumentException(s"Unsupported class wrapper definition: $c"))

        inner(c).impl.body
      case other => throw new IllegalArgumentException(s"Unsupported wrapper definition: $other")
    }
    val symbols0 = stats.filter(x => !Option(x.symbol).exists(_.isPrivate))
                       .foldLeft(List.empty[(String, String, String, Boolean)]){
      // These are all the ways we want to import names from previous
      // executions into the current one. Most are straightforward, except
      // `import` statements for which we make use of the typechecker to
      // resolve the imported names
      case (ctx, t @ g.Import(expr, selectors)) =>
        def rec(expr: g.Tree): List[g.Name] = {
          expr match {
            case g.Select(lhs, name) => name :: rec(lhs)
            case g.Ident(name) => List(name)
            case g.This(pkg) => List(pkg)
          }
        }
        val prefix = rec(expr).reverseMap(x => Parsers.backtickWrap(x.decoded)).mkString(".")
        val renamings =
          for (g.ImportSelector(name, _, rename, _) <- selectors if rename != null)
            yield rename.decoded -> name.decoded
        val renameMap = renamings.toMap
        val info = new g.analyzer.ImportInfo(t, 0)

        val symNames = for {
          sym <- info.allImportedSymbols
          if !sym.isSynthetic
          if !sym.isPrivate
          if sym.isPublic
        } yield (sym.decodedName, sym.isImplicit)

        val symNamesSet = symNames.map(_._1).toSet

        val syms = for{
          // For some reason `info.allImportedSymbols` does not show imported
          // type aliases when they are imported directly e.g.
          //
          // import scala.reflect.macros.Context
          //
          // As opposed to via import scala.reflect.macros._.
          // Thus we need to combine allImportedSymbols with the renameMap
          (sym, isImplicit) <- symNames.toList ++ renameMap.keys.filterNot(symNamesSet.contains).map(s => (s, false /* ??? */))
        } yield {
          (renameMap.getOrElse(sym, sym), sym, prefix, isImplicit)
        }
        syms ::: ctx
      case (ctx, t @ g.DefDef(_, _, _, _, _, _))  => decode(t) :: ctx
      case (ctx, t @ g.ValDef(_, _, _, _))        => decode(t) :: ctx
      case (ctx, t @ g.ClassDef(_, _, _, _))      => decode(t) :: ctx
      case (ctx, t @ g.ModuleDef(_, _, _))        => decode(t) :: ctx
      case (ctx, t @ g.TypeDef(_, _, _, _))       => decode(t) :: ctx
      case (ctx, t) => ctx
    }

    val symbolsImplicit = symbols0.groupBy{case (fromName, toName, prefix, _) => (fromName, toName, prefix)}.mapValues(_.exists(_._4))
    val symbols = symbols0.map{case (fromName, toName, prefix, _) => (fromName, toName, prefix, symbolsImplicit((fromName, toName, prefix)))}

    output(
      for {
        (fromName, toName, importString, isImplicit) <- symbols
        //              _ = println(fromName + "\t"+ toName)

        if !fromName.contains("$")
        if fromName != "<init>"
        if fromName != "<clinit>"
        if fromName != "$main"
      } yield Import(fromName, toName, "", importString, isImplicit)
    )
  }
}
