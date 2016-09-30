package org.tresql.compiling

import org.tresql.parsing._
import org.tresql.metadata._
import org.tresql.{Env, Result, RowLike}
import scala.reflect.ManifestFactory

trait Scope {
  def parent: Scope
  def table(table: String): Option[Table]
  def column(col: String): Option[Col[_]]
  def procedure(procedure: String): Option[Procedure[_]]
}
trait CompiledResult[T <: RowLike] extends Result with Iterator[T] {
  override def toList: List[T] = Nil
}

trait Compiler extends QueryParsers with ExpTransformer with Scope { thisCompiler =>

  var nameIdx = 0
  val metadata = Env.metaData

  trait TypedExp[T] extends Exp {
    def exp: Exp
    def typ: Manifest[T]
    def tresql = exp.tresql
  }
  //helper class for namer to distinguish table references from column references
  case class TableObj(obj: Exp) extends Exp {
    def tresql = obj.tresql
  }
  case class ColDef[T](name: String, exp: Col)(implicit val typ: Manifest[T]) extends TypedExp[T]
  case class ChildDef(exp: Exp) extends TypedExp[ChildDef] {
    val typ: Manifest[ChildDef] = ManifestFactory.classType(this.getClass)
  }
  case class FunDef[T](name: String, exp: Fun)(implicit val typ: Manifest[T]) extends TypedExp[T]
  case class TableDef(name: String, exp: Obj) extends Exp { def tresql = exp.tresql }
  trait SelectDefBase extends TypedExp[SelectDefBase] {
    def cols: List[ColDef[_]]
    val typ: Manifest[SelectDefBase] = ManifestFactory.classType(this.getClass)
  }
  case class SelectDef(
    cols: List[ColDef[_]],
    tables: List[TableDef],
    exp: Query,
    parent: Scope) extends SelectDefBase with Scope {

    //check for duplicating tables
    {
      val duplicates = tables.groupBy(_.name).filter(_._2.size > 1).map(_._1)
      assert(duplicates.size == 0, s"Duplicate table names: ${duplicates.mkString(", ")}")
    }

    def table(table: String) = tables.find(_.name == table).flatMap {
      case TableDef(_, Obj(TableObj(Ident(name)), _, _, _, _)) => parent.table(name mkString ".")
      case TableDef(n, Obj(TableObj(s: SelectDefBase), _, _, _, _)) => Option(table_from_selectdef(n, s))
    } orElse parent.table(table)
    def column(col: String) = col.lastIndexOf('.') match {
      case -1 => tables.collect {
        case TableDef(t, _) => table(t).flatMap(_.colOption(col))
      } collect { case Some(col) => col } match {
        case List(col) => Some(col)
        case Nil => None
        case x => sys.error(s"Ambiguous columns: $x")
      }
      case x => table(col.substring(0, x)).flatMap(_.colOption(col.substring(x + 1)))
    }

    def procedure(procedure: String) = parent.procedure(procedure)

    private def table_from_selectdef(name: String, sd: SelectDefBase) =
      Table(name, sd.cols map col_from_coldef, null, Map())
    private def col_from_coldef(cd: ColDef[_]) =
      org.tresql.metadata.Col(name = cd.name, true, -1, scalaType = cd.typ)
  }
  case class BinSelectDef(
    leftOperand: SelectDefBase,
    rightOperand: SelectDefBase,
    exp: BinOp) extends SelectDefBase {

    assert (leftOperand.cols.exists {
        case ColDef(_, Col(All | _: IdentAll, _, _)) => true
        case _ => false
      } || rightOperand.cols.exists {
        case ColDef(_, Col(All | _: IdentAll, _, _)) => true
        case _ => false
      } || leftOperand.cols.size == rightOperand.cols.size,
      s"Column count do not match ${leftOperand.cols.size} != ${rightOperand.cols.size}")
    val cols = leftOperand.cols
  }

  def table(table: String) = metadata.tableOption(table)
  def column(col: String) = metadata.colOption(col)
  def procedure(procedure: String) = metadata.procedureOption(procedure)
  def parent = null

  def buildTypedDef(exp: Exp) = {
    trait Ctx
    object QueryCtx extends Ctx //root context
    object TablesCtx extends Ctx //from clause
    object ColsCtx extends Ctx //column clause
    object BodyCtx extends Ctx //where, group by, having, order, limit clauses
    val ctx = scala.collection.mutable.Stack[Ctx](QueryCtx)

    def tr(x: Any): Any = x match {case e: Exp @unchecked => builder(e) case _ => x} //helper function
    lazy val builder: PartialFunction[Exp, Exp] = transformer {
      case f: Fun => procedure(f.name).map { p =>
        FunDef(p.name, f.copy(parameters = f.parameters map tr))(p.scalaReturnType)
      }.getOrElse(sys.error(s"Unknown function: ${f.name}"))
      case c: Col =>
        val alias = if (c.alias != null) c.alias else c.col match {
          case Obj(Ident(name), _, _, _, _) => name mkString "."
          case _ => null
        }
        ColDef(alias, c.copy(col = tr(c.col)))(
          if(c.typ != null) metadata.xsd_scala_type_map(c.typ) else ManifestFactory.Nothing)
      case Obj(b: Braces, _, _, _, _) if ctx.head == QueryCtx =>
        builder(b) //unwrap braces top level expression
      case o: Obj if ctx.head == QueryCtx | ctx.head == TablesCtx => //obj as query
        builder(Query(List(o), null, null, null, null, null, null))
      case o: Obj if ctx.head == BodyCtx =>
        o.copy(obj = builder(o.obj), join = builder(o.join).asInstanceOf[Join])
      case q: Query =>
        ctx push TablesCtx
        val tables = q.tables map { table =>
          val newTable = builder(table.obj)
          ctx push BodyCtx
          val join = tr(table.join).asInstanceOf[Join]
          ctx.pop
          val name = Option(table.alias).getOrElse(table match {
            case Obj(Ident(name), _, _, _, _) => name mkString "."
            case _ => sys.error(s"Alias missing for from clause select: ${table.tresql}")
          })
          TableDef(name, table.copy(obj = TableObj(newTable), join = join))
        }
        ctx.pop
        ctx push ColsCtx
        val cols =
          if (q.cols != null) (q.cols.cols map builder).asInstanceOf[List[ColDef[_]]]
          else List[ColDef[_]](ColDef(null, Col(All, null, null))(null))
        ctx.pop
        ctx push BodyCtx
        val (filter, grp, ord, limit, offset) =
          (tr(q.filter).asInstanceOf[Filters],
           tr(q.group).asInstanceOf[Grp],
           tr(q.order).asInstanceOf[Ord],
           tr(q.limit),
           tr(q.offset))
        ctx.pop
        SelectDef(
          cols,
          tables,
          Query(
            tables = Nil,
            filter = filter,
            cols = null,
            group = grp,
            order = ord,
            limit = limit,
            offset = offset),
          null)
      case b: BinOp =>
        (tr(b.lop), tr(b.rop)) match {
          case (lop: SelectDefBase, rop: SelectDefBase) =>
            BinSelectDef(lop, rop, b.copy(lop = lop, rop = rop))
          //TODO process braces expr!!!
          case (lop, rop) => b.copy(lop = lop, rop = rop)
        }
      case UnOp("|", o: Exp @unchecked) if ctx.head == ColsCtx => ChildDef(builder(o))
      case Braces(exp: Exp) if ctx.head == TablesCtx => builder(exp) //remove braces around table expression, so it can be accessed directly
      case null => null
    }
    builder(exp)
  }

  def resolveScopes(exp: Exp) = {
    val scope_stack = scala.collection.mutable.Stack[Scope](thisCompiler)
    def tr(x: Any): Any = x match {case e: Exp @unchecked => scoper(e) case _ => x} //helper function
    lazy val scoper: PartialFunction[Exp, Exp] = transformer {
      case sd: SelectDef =>
        val nsd = sd.copy(parent = scope_stack.head)
        val t = (nsd.tables map scoper).asInstanceOf[List[TableDef]]
        scope_stack push nsd
        val c = (nsd.cols map scoper).asInstanceOf[List[ColDef[_]]]
        val q = scoper(nsd.exp).asInstanceOf[Query]
        scope_stack.pop
        nsd.copy(cols = c, tables = t, exp = q)
    }
    scoper(exp)
  }

  def resolveColAsterisks(exp: Exp) = {
    lazy val resolver: PartialFunction[Exp, Exp] = transformer {
      case sd: SelectDef =>
        val nsd = sd.copy(tables = {
          sd.tables.map {
            case td @ TableDef(_, o @ Obj(TableObj(sdb: SelectDefBase), _, _, _, _)) =>
              td.copy(exp = o.copy(obj = resolver(sdb)))
            case td => td
          }
        })
        nsd.copy (cols = {
          nsd.cols.flatMap {
            case ColDef(_, Col(All, n, _)) =>
              val prefix = if (n == null) "" else n + "_"
              nsd.tables.flatMap { td =>
                val table = nsd.table(td.name).getOrElse(sys.error(s"Cannot find table: $td"))
                val name_prefix = prefix +
                  (if (nsd.tables.size > 1) td.name.replace('.', '_') + "_" else "")
                table.cols.map(c => ColDef(name_prefix + c.name, null)(c.scalaType))
              }
            case ColDef(_, Col(IdentAll(Ident(ident)), _, _)) =>
              val name_prefix = ident.mkString("", "_", "_")
              nsd.table(ident mkString ".")
                .map(_.cols.map(c => ColDef(name_prefix + c.name, null)(c.scalaType)))
                .getOrElse(sys.error(s"Cannot find table: ${ident mkString "."}"))
            case cd @ ColDef(_, c @ Col(chd: ChildDef, _, _)) =>
              List(cd.copy(exp = c.copy(col = resolver(chd))))
            case cd => List(cd)
          }
        })
    }
    resolver(exp)
  }

  def resolveNames(exp: Exp) = {
    trait Ctx
    object TableCtx extends Ctx
    object ColumnCtx extends Ctx
    case class Context(scope: Scope, ctx: Ctx)
    lazy val namer: PartialFunction[(Context, Exp), Context] = extractor {
      case (ctx, sd: SelectDef) => ctx.copy(scope = sd) //set new scope
      case (ctx, _: TableObj) => ctx.copy(ctx = TableCtx) //set table context
      case (ctx, _: Obj) => ctx.copy(ctx = ColumnCtx) //set column context
      case (ctx @ Context(scope, TableCtx), Ident(ident)) => //check table
        val tn = ident mkString "."
        scope.table(tn).orElse(sys.error(s"Unknown table: $tn"))
        ctx
      case (ctx @ Context(scope, ColumnCtx), Ident(ident)) => //check column
        val cn = ident mkString "."
        scope.column(cn).orElse(sys.error(s"Unknown column: $cn"))
        ctx
    }
    namer(Context(thisCompiler, ColumnCtx) -> exp)
  }

  def compile(exp: Exp) = {
    val compiledExp = resolveColAsterisks(
      resolveScopes(
        buildTypedDef(
          exp)))
    resolveNames(compiledExp)
    compiledExp
  }

  override def transformer(fun: PartialFunction[Exp, Exp]): PartialFunction[Exp, Exp] = {
    lazy val local_transformer = fun orElse traverse
    lazy val transform_traverse = local_transformer orElse super.transformer(local_transformer)
    lazy val traverse: PartialFunction[Exp, Exp] = {
      case cd: ColDef[_] => cd.copy(exp = transform_traverse(cd.exp).asInstanceOf[Col])
      case cd: ChildDef => cd.copy(exp = transform_traverse(cd.exp))
      case fd: FunDef[_] => fd.copy(exp = transform_traverse(fd.exp).asInstanceOf[Fun])
      case td: TableDef => td.copy(exp = transform_traverse(td.exp).asInstanceOf[Obj])
      case to: TableObj => to.copy(obj = transform_traverse(to.obj))
      case sd: SelectDef =>
        val t = (sd.tables map transform_traverse).asInstanceOf[List[TableDef]]
        val c = (sd.cols map transform_traverse).asInstanceOf[List[ColDef[_]]]
        val q = transform_traverse(sd.exp).asInstanceOf[Query]
        sd.copy(cols = c, tables = t, exp = q)
      case bd: BinSelectDef => bd.copy(
        leftOperand = transform_traverse(bd.leftOperand).asInstanceOf[SelectDefBase],
        rightOperand = transform_traverse(bd.rightOperand).asInstanceOf[SelectDefBase])
    }
    transform_traverse
  }

  override def extractor[T](
    fun: PartialFunction[(T, Exp), T],
    traverser: PartialFunction[(T, Exp), T] = PartialFunction.empty): PartialFunction[(T, Exp), T] = {
    def tr(r: T, x: Any): T = x match {
      case e: Exp => extract_traverse((r, e))
      case l: List[_] => l.foldLeft(r) { (fr, el) => tr(fr, el) }
      case _ => r
    }
    lazy val extract_traverse: PartialFunction[(T, Exp), T] =
      super.extractor(fun, traverser orElse local_extract_traverse)
    lazy val local_extract_traverse: PartialFunction[(T, Exp), T] = {
      case (r: T, cd: ColDef[_]) => tr(r, cd.exp)
      case (r: T, cd: ChildDef) => tr(r, cd.exp)
      case (r: T, fd: FunDef[_]) => tr(r, fd.exp)
      case (r: T, td: TableDef) => tr(r, td.exp)
      case (r: T, to: TableObj) => tr(r, to.obj)
      case (r: T, sd: SelectDef) => tr(tr(tr(r, sd.tables), sd.cols), sd.exp)
      case (r: T, bd: BinSelectDef) => tr(tr(r, bd.leftOperand), bd.rightOperand)
    }
    extract_traverse
  }

  def parseExp(expr: String): Any = try {
    intermediateResults.get.clear
    phrase(exprList)(new scala.util.parsing.input.CharSequenceReader(expr)) match {
      case Success(r, _) => r
      case x => sys.error(x.toString)
    }
  } finally intermediateResults.get.clear
}