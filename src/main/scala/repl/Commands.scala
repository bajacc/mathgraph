package mathgraph.repl

import CommandLexer._
import io.AnsiColor._
import mathgraph.corelogic._
import mathgraph.printer._
import mathgraph.solver._
import mathgraph.solver.Solver._

object Commands {

  /** Represents the type of parameters to commands (can be extended to floats, booleans, ...) */
  abstract class ParamType
  case object IntT extends ParamType {
    override def toString: String = "int"
  }
  case object StringT extends ParamType {
    override def toString: String = "string"
  }

  /** A command takes a logic state and some parameters and returns the new state */
  type Command = LogicState => Seq[Any] => LogicState

  /** Generic class used to define commands */
  case class CommandDef private (
      name: String,
      desc: String,
      mandatoryParams: Seq[ParamType],
      optionalParams: Seq[ParamType],
      command: Command
  ) {
    def ~>(params: ParamType*): CommandDef =
      copy(mandatoryParams = params)
    def ~>?(params: ParamType*): CommandDef =
      copy(optionalParams = params)
    def ??(str: String): CommandDef = copy(desc = str)
  }

  object CommandDef {
    def apply(name: String, command: Command): CommandDef =
      CommandDef(name, "", Seq(), Seq(), command)
  }

  // This is used to align all the commands based on the longest command name
  lazy val maxCommandLength = commands.keys.map(_.length).max

  // Transforms a sequence of definitions into a map from name to command def
  private def cmdsToMap(defs: CommandDef*): Map[String, CommandDef] =
    defs.map(df => df.name -> df).toMap

  // Prints a short summary of what the given command does
  def printSummary(df: CommandDef): Unit = {
    val padded = df.name.padTo(maxCommandLength, ' ')
    val alignedDesc =
      df.desc.replaceAll("\n", "\n" + " " * (maxCommandLength + 4))
    println(s"- $padded  $alignedDesc")
  }

  // Prints a detailed description of the given command
  def printUsage(df: CommandDef): Unit = {
    val mandatory = df.mandatoryParams.map(tpe => s"<$tpe>")
    val optional = df.optionalParams.map(tpe => s"[<$tpe>]")
    println((df.name +: (mandatory ++ optional)).mkString(" "))
    println(df.desc)
  }

  // Prints the logic state to the console
  def printState(
      ls: LogicState,
      exprs: Iterable[Int],
      simple: Boolean
  ): Unit = {
    val lg = ls.logicGraph
    val printer: Int => String =
      if (simple) ls.printer.toSimpleString(lg, _)
      else ls.printer.toString(lg, _)

    for (e <- exprs) {
      val truth = lg
        .getTruthOf(e)
        .map(t => if (t) "[true]\t" else "[false]\t")
        .getOrElse("\t\t")

      val definition =
        ls.printer.getDefinition(lg, e).map(d => s" := $d").getOrElse("")
      println(f"$e%04d $truth ${printer(e)}$definition")
    }
  }

  // Helper function to create commands
  def transformState(f: LogicState => LogicState): Command = { ls =>
    { case Seq() =>
      f(ls)
    }
  }
  def consumeState(f: LogicState => Unit): Command = transformState { ls =>
    f(ls); ls
  }

  // -------------------------------------------------------------
  // Command defintions. Add new commands here.
  // -------------------------------------------------------------

  val help: Command = { ls =>
    {
      case Seq() =>
        println("Available commands :")
        commands.values.foreach(printSummary)
        ls
      case Seq(cmd: String) =>
        commands
          .get(cmd)
          .map(printUsage)
          .getOrElse(println(s"Command '$cmd' does not exist"))
        ls
    }
  }

  val lss: Command = consumeState { ls =>
    printState(ls, 0 until ls.logicGraph.size, simple = true)
  }

  val ls: Command = consumeState { ls =>
    printState(ls, 0 until ls.logicGraph.size, simple = false)
  }

  val absurd: Command = consumeState { ls =>
    val status = if (ls.logicGraph.isAbsurd) "absurd" else "not absurd"
    println(s"The logic graph is $status")
  }

  val fixN: Command = { ls =>
    { case Seq(e: Int) =>
      ls.logicGraph.fixLetSymbol(e)
      ls
    }
  }

  val fix: Command = { ls =>
    { case Seq(e1: Int, e2: Int) =>
      ls.logicGraph.fix(e1, e2)
      ls
    }
  }

  val simplify: Command = { ls =>
    { case Seq(e: Int) =>
      ls.logicGraph.simplifyInferenceRule(e)
      ls
    }
  }

  val fixLogicExpr: Command = consumeState { ls =>
    ls.solver.fixLogicExpr()(ls.logicGraph)
  }

  val fixAllFalse: Command = consumeState { ls =>
    ls.solver.fixLetSym()(ls.logicGraph)
  }

  val fixForallExpr: Command = consumeState { ls =>
    ls.solver.fixForallExpr()(ls.logicGraph)
  }

  val evaluateImply: Command = consumeState { ls =>
    ls.solver.evaluateAllImply()(ls.logicGraph)
  }

  val proof: Command = consumeState { ls =>
    ls.printer.proofAbsurd(ls.logicGraph).foreach(println)
  }

  val saturate: Command = consumeState { ls =>
    {
      ls.solver.saturation(ls.logicGraph)
      proof(ls)(Seq())
    }
  }

  val psol: Command = consumeState { ls =>
    {
      val lg = ls.logicGraph
      val sol = ls.solver
      sol.update(lg)
      println("imply exprs")
      printState(ls, sol.logicExpr, simple = false)
      println("forall exprs")
      printState(ls, sol.forallExpr, simple = false)
      println("exists exprs")
      printState(ls, sol.existsExpr, simple = false)
      println("true global exprs")
      printState(ls, sol.trueGlobalExpr, simple = false)
      println("false global exprs")
      printState(ls, sol.falseGlobalExpr, simple = false)
    }
  }

  /** Contains the list of all the available commands in the REPL */
  val commands: Map[String, CommandDef] = cmdsToMap(
    CommandDef("help", help) ~>? StringT ??
      """Provides help about commands. Type 'help' for general help
        |or 'help <cmd>' for help about a specific command.""".stripMargin,
    CommandDef("exit", ls => _ => ls) ??
      "Exits the REPL",
    CommandDef("lss", lss) ??
      "Displays all the expression in a simple way.",
    CommandDef("ls", ls) ??
      "Displays all the expression.",
    CommandDef("psol", psol) ??
      "Displays solver expressions.",
    CommandDef("abs", absurd) ??
      "Displays whether the set of expressions is absurd.",
    CommandDef("fixn", fixN) ~> IntT ??
      "Fixes the given symbol with its let-symbol.",
    CommandDef("fix", fix) ~> (IntT, IntT) ??
      "Fixes the two symbols given as arguments.",
    CommandDef("simp", simplify) ~> IntT ??
      "Simplifies the given expression.",
    CommandDef("fle", fixLogicExpr) ??
      "Fixes all the expressions to true.",
    CommandDef("ffe", fixForallExpr) ??
      "Fixes all the expressions to true.",
    CommandDef("evi", evaluateImply) ??
      "evaluate expressions of the form a -> b.",
    CommandDef("faf", fixAllFalse) ??
      "Fixes all the expressions to false.",
    CommandDef("proof", proof) ??
      "Displays a proof by contradiction, if one was found.",
    CommandDef("s", saturate) ??
      "Applies the saturation algorithm to the set of expressions."
  )
}
