package org.moe.parser

import scala.util.parsing.combinator._
import org.moe.ast._

trait MoeProductions extends MoeLiterals with JavaTokenParsers with PackratParsers {

  /**
   *********************************************************************
   * This part of the parser deals mostly with 
   * Ternary, Unary and Binary operations and
   * implements the precedance ordering.
   *********************************************************************
   */
  
  lazy val expression: PackratParser[AST] = matchOp | assignOp

  // TODO: left        or xor
  // TODO: left        and
  // TODO: right       not
  // TODO: nonassoc    list operators (rightward)
  // TODO: left        , =>
  // TODO: right       = += -= *= etc.

  // right       =  (assignment)
  lazy val assignOp: PackratParser[AST] = lvalue ~ "=" ~ assignOp ^^ {
    case left ~ "=" ~ right => BinaryOpNode(left, "=", right)
  } | ternaryOp

  // right       ?:
  lazy val ternaryOp: PackratParser[AST] = logicalOrOp ~ "?" ~ ternaryOp ~ ":" ~ ternaryOp ^^ {
    case cond ~ "?" ~ trueExpr ~ ":" ~ falseExpr => TernaryOpNode(cond, trueExpr, falseExpr)
  } | logicalOrOp

  // left        ||           TODO: //
  lazy val logicalOrOp: PackratParser[AST] = logicalOrOp ~ """\|\||//""".r ~ logicalAndOp ^^ {
    case left ~ op ~ right => ShortCircuitBinaryOpNode(left, op, right)
  } | logicalAndOp

  // left        &&
  lazy val logicalAndOp: PackratParser[AST] = logicalAndOp ~ "&&" ~ bitOrOp ^^ {
    case left ~ op ~ right => ShortCircuitBinaryOpNode(left, op, right)
  } | bitOrOp

  // left        | ^
  lazy val bitOrOp: PackratParser[AST] = bitOrOp ~ "[|^]".r ~ bitAndOp ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | bitAndOp

  // left        &
  lazy val bitAndOp: PackratParser[AST] = bitAndOp ~ "&" ~ eqOp ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | eqOp

  // nonassoc    == != eq ne cmp ~~
  lazy val eqOp: PackratParser[AST] = eqOp ~ "[!=]=|<=>|eq|ne|cmp".r ~ relOp ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | relOp

  // nonassoc    < > <= >= lt gt le ge
  lazy val relOp: PackratParser[AST] = relOp ~ "[<>]=?|lt|gt|le|ge".r ~ bitShiftOp ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | bitShiftOp

  // TODO: nonassoc    named unary operators

  // left        << >>
  lazy val bitShiftOp: PackratParser[AST] = bitShiftOp ~ "<<|>>".r ~ addOp            ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | addOp

  // left        + - ~
  lazy val addOp: PackratParser[AST] = addOp ~ "[-+~]".r ~ mulOp            ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | mulOp

  // left        * / % x
  lazy val mulOp: PackratParser[AST] = mulOp ~ "[*/%x]".r ~ expOp ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | expOp

  // TODO: right       ! ~ \ and unary + and -

  // This one is right-recursive (associative) instead of left
  // right       **
  lazy val expOp: PackratParser[AST] = coerceOp ~ "**" ~ expOp ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  } | coerceOp

  // Symbolic unary -- left        + (num), ? (bool), ~ (str)
  // used for explicit coercion
  // (see: http://perlcabal.org/syn/S03.html#Symbolic_unary_precedence)

  lazy val coerceOp: PackratParser[AST] = "[+?~]".r ~ fileTestOps ^^ {
    case op ~ expr => PrefixUnaryOpNode(expr, op)
  } | fileTestOps

  // TODO: nonassoc    ++ --

  lazy val fileTestOps: PackratParser[AST] = "-[erwx]".r ~ applyOp ^^ {
    case op ~ expr => PrefixUnaryOpNode(expr, op)
  } | applyOp

  /**
   *********************************************************************
   * Here we have method and subroutine calling
   * both of which are still expressions.
   *********************************************************************
   */

  // left        .
  lazy val applyOp: PackratParser[AST] = (applyOp <~ ".") ~ identifier ~ ("(" ~> repsep(expression, ",") <~ ")").? ^^ {
    case invocant ~ method ~ Some(args) => MethodCallNode(invocant, method, args)
    case invocant ~ method ~ None       => MethodCallNode(invocant, method, List())
  } | quoteExpression | subroutineCall

  lazy val subroutineCall: PackratParser[AST] = namespacedIdentifier ~ ("(" ~> repsep(expression, ",") <~ ")") ^^ {
    case sub ~ args => SubroutineCallNode(sub, args)
  } | anonCodeCall

  def anonCodeInvocant: PackratParser[AST] = (
      variable
    | attribute
  )

  lazy val anonCodeCall: PackratParser[AST] = anonCodeInvocant ~ "." ~ ("(" ~> repsep(expression, ",") <~ ")") ^^ {
    case anonCode ~ _ ~ args => MethodCallNode(anonCode, "call", args)
  } | listOpLeftward

  // left        terms and list operators (leftward)
  lazy val listOpLeftward: PackratParser[AST] = namespacedIdentifier ~ rep1sep(expression, ",") ^^ {
    case sub ~ args => SubroutineCallNode(sub, args)
  } | simpleExpression


  /**
   *********************************************************************
   * Now build our set of simpleExpressions
   * which are not Unary, Binary or Ternay
   * This includes much of what comes after 
   * it in this file.
   *********************************************************************
   */

  lazy val simpleExpression: PackratParser[AST] = (
      quoteExpression
    | arrayIndex
    | hashIndex
    | hash
    | array
    | pair
    | range
    | code
    | literalValue
    | classAccess
    | variable
    | attribute
    | specialVariable    
    | expressionParens
    | signedExpressionParens
  )

  def expressionParens: Parser[AST] = "(" ~> expression <~ ")"
  def signedExpressionParens: PackratParser[AST] = "[-+!]".r ~ expressionParens ^^ {
    case "+" ~ expr => expr
    case "-" ~ expr => PrefixUnaryOpNode(expr, "-")
    case "!" ~ expr => PrefixUnaryOpNode(expr, "!")
  }

  /**
   *********************************************************************
   * Now we get into a lot of creation of 
   * complex literal values such as Hash,
   * Pair, Array, Code, etc
   *********************************************************************
   */

  // List/Array Literals

  def list: Parser[List[AST]] = (literal(",").? ~> repsep(expression, ",") <~ literal(",").?)
  def array: Parser[ArrayLiteralNode] = "[" ~> list <~ "]" ^^ ArrayLiteralNode

  // Pair Literals

  def pair: Parser[PairLiteralNode] = (hashKey <~ "=>") ~ expression ^^ { 
    case k ~ v => PairLiteralNode(k, v) 
  }

  // Hash Literals
  
  def barehashKey: Parser[StringLiteralNode] = """[0-9\w_]+""".r ^^ StringLiteralNode
  def hashKey: Parser[AST] = variable | arrayIndex | hashIndex | literalValue | barehashKey

  def hashContent: Parser[List[PairLiteralNode]] = repsep(pair, ",")
  def hash: Parser[HashLiteralNode] = "{" ~> hashContent <~ "}" ^^ HashLiteralNode

  // Range Literals

  def range: Parser[RangeLiteralNode] = simpleExpression ~ ".." ~ simpleExpression ^^ {
    case s ~ _ ~ e => RangeLiteralNode(s, e)
  }

  /**
   *********************************************************************
   * This section begins to deal with variable
   * declaration and access.
   *********************************************************************
   */

  def identifier           = """[a-zA-Z_][a-zA-Z0-9_]*""".r
  def namespaceSeparator   = "::"
  def namespacedIdentifier = rep1sep(identifier, namespaceSeparator) ^^ { _.mkString(namespaceSeparator) }
  
  // access

  def variableName = """[$@%&][a-zA-Z_][a-zA-Z0-9_]*""".r ^^ { v => VariableNameNode(v) }
  def variable     = variableName ^^ {
    case VariableNameNode(v) => VariableAccessNode(v)
  }

  def specialVariableName = ("""[$@%&][?*][A-Z_]+""".r | "$!") ^^ { v => VariableNameNode(v) }
  def specialVariable     = specialVariableName ^^ {
    case VariableNameNode(v) => VariableAccessNode(v)
  }

  def attributeName = """[$@%&]![a-zA-Z_][a-zA-Z0-9_]*""".r ^^ { a => AttributeNameNode(a) }
  def attribute     = attributeName ^^ {
    case AttributeNameNode(a) =>  AttributeAccessNode(a)
  }

  def parameterName = """([$@%&])([a-zA-Z_][a-zA-Z0-9_]*)""".r

  def classAccess = namespacedIdentifier ^^ ClassAccessNode

  // declaration

  def arrayVariableName = """@[a-zA-Z_][a-zA-Z0-9_]*""".r
  def hashVariableName  = """%[a-zA-Z_][a-zA-Z0-9_]*""".r

  def array_index_rule = arrayVariableName ~ ( "[" ~> expression <~ "]" ).+ ^^ {
    case a ~ i => ArrayElementNameNode(a, i)
  }
  def hash_index_rule  = hashVariableName  ~ ( "{" ~> expression <~ "}" ).+ ^^ {
    case h ~ k => HashElementNameNode(h, k)
  }

  def arrayIndex = array_index_rule ^^ {
    case ArrayElementNameNode(i, exprs) => ArrayElementAccessNode(i, exprs)
  }

  def hashIndex = hash_index_rule ^^ {
    case HashElementNameNode(h, exprs) => HashElementAccessNode(h, exprs)
  }

  def lvalue: Parser[AST] = (
      array_index_rule
    | hash_index_rule
    | attributeName
    | variableName
    | specialVariableName
  )

  // assignment

  def variableDeclaration = "my" ~> variableName ~ ("=" ~> expression).? ^^ {
    case VariableNameNode(v) ~ expr => VariableDeclarationNode(v, expr.getOrElse(UndefLiteralNode()))
  }  

  private def zipEm (x: List[String], y: List[AST], f: ((String, AST)) => AST): List[AST] = {
    if (y.isEmpty) 
      x.map(f(_, UndefLiteralNode())) 
    else if (x.isEmpty) 
      List() 
    else 
      f(x.head, y.headOption.getOrElse(UndefLiteralNode())) :: zipEm(x.tail, y.tail, f)
  }

  def multiVariableDeclaration = "my" ~> ("(" ~> repsep(variableName, ",") <~ ")") ~ ("=" ~> "(" ~> repsep(expression, ",") <~ ")").? ^^ {
    case vars ~ None        => StatementsNode(
      vars.map( { case VariableNameNode(v) => VariableDeclarationNode(v, UndefLiteralNode()) } )
    )
    case vars ~ Some(exprs) => StatementsNode(
      zipEm(vars.map( { case VariableNameNode(v) => v } ),
            exprs,
            (p) => VariableDeclarationNode(p._1, p._2))
    )
  }  

  def multiVariableAssignment = ("(" ~> repsep(variableName, ",") <~ ")") ~ "=" ~ ("(" ~> repsep(expression, ",") <~ ")") ^^ {
    case vars ~ _ ~ exprs => MultiVariableAssignmentNode(
      vars.map( { case VariableNameNode(vname) => vname } ),
      exprs
    )
  }    

  def multiAttributeAssignment = ("(" ~> repsep(attributeName, ",") <~ ")") ~ "=" ~ ("(" ~> repsep(expression, ",") <~ ")") ^^ {
    case vars ~ _ ~ exprs => MultiAttributeAssignmentNode(vars.map({case AttributeNameNode(aname) => aname}), exprs)
  }   

  /**
   * regex match/substitution etc
   */

  lazy val regexModifiers: Parser[AST] = """[igsmx]*""".r ^^ { flags => StringLiteralNode(flags) }

  def matchExpression: Parser[AST] = (("m" ~> quotedString) | quoted('/')) ~ opt(regexModifiers) ^^ {
      case pattern ~ None        => MatchExpressionNode(RegexLiteralNode(pattern), StringLiteralNode(""))
      case pattern ~ Some(flags) => MatchExpressionNode(RegexLiteralNode(pattern), flags)
    }

  def substExpression_1: Parser[AST] = ("s" ~> quotedPair('/')) ~ opt(regexModifiers) ^^ {
    case (pattern, replacement) ~ None        => SubstExpressionNode(RegexLiteralNode(pattern), StringLiteralNode(replacement), StringLiteralNode(""))
    case (pattern, replacement) ~ Some(flags) => SubstExpressionNode(RegexLiteralNode(pattern), StringLiteralNode(replacement), flags)
  }

  def substExpression_2: Parser[AST] = ("s" ~> bracketedString) ~ bracketedString ~ opt(regexModifiers) ^^ {
    case pattern ~ replacement ~ None        => SubstExpressionNode(RegexLiteralNode(pattern), StringLiteralNode(replacement), StringLiteralNode(""))
    case pattern ~ replacement ~ Some(flags) => SubstExpressionNode(RegexLiteralNode(pattern), StringLiteralNode(replacement), flags)
  }

  private def splitString(str: String) = str.split(" ").map(s => StringLiteralNode(s)).toList

  def quoteOp = "q[qwx]?".r ~ quotedString ^^ {
    case "qq" ~ expr => MoeStringParser.interpolateStr(expr)
    case "qw" ~ expr => ArrayLiteralNode(splitString(expr))
    // this is naive; doesn't handle embedded spaces in args
    case "qx" ~ expr => ExecuteCommandNode(MoeStringParser.interpolateStr(expr))
    case "q"  ~ expr => StringLiteralNode(expr)
  }
  def quoteRegexOp = "qr" ~ quotedString ~ opt(regexModifiers) ^^ {
    case "qr" ~ expr ~ Some(flags) => MatchExpressionNode(RegexLiteralNode(expr), flags)
    case "qr" ~ expr ~ None        => MatchExpressionNode(RegexLiteralNode(expr), StringLiteralNode(""))
  }

  // TODO: tr (transliteration) operator
  lazy val transModifiers: Parser[AST] = """[cdsr]*""".r ^^ StringLiteralNode

  def transExpression_1 = ("tr" ~> quotedPair('/')) ~ opt(transModifiers) ^^ {
    case (search, replacement) ~ None        => TransExpressionNode(StringLiteralNode(search), StringLiteralNode(replacement), StringLiteralNode(""))
    case (search, replacement) ~ Some(flags) => TransExpressionNode(StringLiteralNode(search), StringLiteralNode(replacement), flags)
  }
  def transExpression_2 = ("tr" ~> bracketedString) ~ bracketedString ~ opt(transModifiers) ^^ {
    case search ~ replacement ~ None        => TransExpressionNode(StringLiteralNode(search), StringLiteralNode(replacement), StringLiteralNode(""))
    case search ~ replacement ~ Some(flags) => TransExpressionNode(StringLiteralNode(search), StringLiteralNode(replacement), flags)
  }

  def quoteExpression = (
      substExpression_1
    | substExpression_2
    | matchExpression
    | transExpression_1
    | transExpression_2
    | quoteOp
    | quoteRegexOp
  )

  def matchOp = simpleExpression ~ "=~" ~ expression ^^ {
    case left ~ op ~ right => BinaryOpNode(left, op, right)
  }

  /**
   *********************************************************************
   * Now we are getting into statements,
   * however the line is a little blurred 
   * here by the "code" production because
   * it is referenced above in "simpleExpressions"
   *********************************************************************
   */

  def statementDelim: Parser[List[String]] = rep1(";")

  def statements: Parser[StatementsNode] = rep((
      blockStatement
    | declarationStatement
    | terminatedStatement
    | statement
    | scopeBlock
  )) ~ opt(statement) ^^ {
    case stmts ~ Some(lastStmt) => StatementsNode(stmts ++ List(lastStmt))
    case stmts ~ None           => StatementsNode(stmts)
  }

  def block: Parser[StatementsNode] = ("{" ~> statements <~ "}")

  def doBlock: Parser[StatementsNode] = "do".r ~> block
  def scopeBlock: Parser[ScopeNode] = block ^^ { ScopeNode(_) }

  // versions and authority

  def versionDecl   = """[0-9]+(\.[0-9]+)*""".r
  def authorityDecl = """[a-z]+\:\S*""".r

  // Parameters

  def parameter = ("[*:]".r).? ~ parameterName ~ "?".? ~ opt("=" ~> expression) ^^ { 
    case None      ~ a ~ None      ~ None   => ParameterNode(a)
    case None      ~ a ~ Some("?") ~ None   => ParameterNode(a, optional = true)
    case Some(":") ~ a ~ None      ~ None   => ParameterNode(a, named = true)
    case Some("*") ~ a ~ None      ~ None   => {
      a.take(1) match {
        case "@" => ParameterNode(a, slurpy = true)
        case "%" => ParameterNode(a, slurpy = true, named = true) 
        case  _  => throw new Exception("slurpy parameters must be either arrays or hashes")
      }
    }
    case None     ~ a ~ None       ~ defVal => ParameterNode(a, default = defVal)
  }

  // Code literals

  def code: Parser[CodeLiteralNode] = ("->" ~> ("(" ~> repsep(parameter, ",") <~ ")").?) ~ block ^^ { 
    case Some(p) ~ b => CodeLiteralNode(SignatureNode(p), b) 
    case None    ~ b => CodeLiteralNode(SignatureNode(List(ParameterNode("@_", slurpy = true))), b) 
  }  

  // Packages

  def packageDecl = ("package" ~> namespacedIdentifier ~ ("-" ~> versionDecl).? ~ ("-" ~> authorityDecl).?) ~ block ^^ {
    case p ~ None ~ None ~ b => PackageDeclarationNode(p, b)
    case p ~ v    ~ None ~ b => PackageDeclarationNode(p, b, version = v)
    case p ~ None ~ a    ~ b => PackageDeclarationNode(p, b, authority = a)
    case p ~ v    ~ a    ~ b => PackageDeclarationNode(p, b, version = v, authority = a)
  }

  def subroutineDecl: Parser[SubroutineDeclarationNode] = ("sub" ~> identifier ~ ("(" ~> repsep(parameter, ",") <~ ")").?) ~ rep("is" ~> identifier).? ~ block ^^ { 
    case n ~ Some(p) ~ t ~ b => SubroutineDeclarationNode(n, SignatureNode(p), b, t) 
    case n ~ None    ~ t ~ b => SubroutineDeclarationNode(n, SignatureNode(List()), b, t) 
  }

  // Classes

  def attributeDecl = "has" ~> attributeName ~ ("=" ~> expression).? ^^ {
    case AttributeNameNode(v) ~ expr => AttributeDeclarationNode(v, expr.getOrElse(UndefLiteralNode()))
  }

  def methodDecl: Parser[MethodDeclarationNode] = ("method" ~> identifier ~ ("(" ~> repsep(parameter, ",") <~ ")").?) ~ block ^^ { 
    case n ~ Some(p) ~ b => MethodDeclarationNode(n, SignatureNode(p), b) 
    case n ~ None    ~ b => MethodDeclarationNode(n, SignatureNode(List()), b) 
  }

  def submethodDecl: Parser[SubMethodDeclarationNode] = ("submethod" ~> identifier ~ ("(" ~> repsep(parameter, ",") <~ ")").?) ~ block ^^ { 
    case n ~ Some(p) ~ b => SubMethodDeclarationNode(n, SignatureNode(p), b) 
    case n ~ None    ~ b => SubMethodDeclarationNode(n, SignatureNode(List()), b) 
  }

  def classBodyParts: Parser[AST] = (
      methodDecl
    | submethodDecl
    | (attributeDecl <~ statementDelim)
    | attributeDecl
  )

  def classBodyContent    : Parser[StatementsNode] = rep(classBodyParts) ^^ StatementsNode
  def classBody           : Parser[StatementsNode] = "{" ~> classBodyContent <~ "}"

  def classDecl = ("class" ~> identifier ~ ("-" ~> versionDecl).? ~ ("-" ~> authorityDecl).?) ~ ("extends" ~> namespacedIdentifier).? ~ classBody ^^ {
    case c ~ None ~ None ~ s ~ b => ClassDeclarationNode(c, s, b) 
    case c ~ v    ~ None ~ s ~ b => ClassDeclarationNode(c, s, b, version = v)
    case c ~ None ~ a    ~ s ~ b => ClassDeclarationNode(c, s, b, authority = a) 
    case c ~ v    ~ a    ~ s ~ b => ClassDeclarationNode(c, s, b, version = v, authority = a)
  }

  /**
   *********************************************************************
   * From here on out it is mostly about 
   * control statements.
   *********************************************************************
   */

  def useStatement: Parser[UseStatement] = ("use" ~> namespacedIdentifier) ^^ UseStatement

  def elseBlock: Parser[IfStruct] = "else" ~> block ^^ { 
    case body => new IfStruct(BooleanLiteralNode(true), body) 
  }

  def elsifBlock: Parser[IfStruct] = "elsif" ~> ("(" ~> expression <~ ")") ~ block ~ (elsifBlock | elseBlock).? ^^ {
    case cond ~ body ~ None => new IfStruct(cond, body)
    case cond ~ body ~ more => new IfStruct(cond, body, more)
  }

  def ifElseBlock: Parser[AST] = "if" ~> ("(" ~> expression <~ ")") ~ block ~ (elsifBlock | elseBlock).? ^^ { 
    case if_cond ~ if_body ~ None        => IfNode(new IfStruct(if_cond,if_body)) 
    case if_cond ~ if_body ~ Some(_else) => IfNode(new IfStruct(if_cond,if_body, Some(_else))) 
  }

  def unlessElseBlock: Parser[AST] = "unless" ~> ("(" ~> expression <~ ")") ~ block ~ (elsifBlock | elseBlock).? ^^ { 
    case unless_cond ~ unless_body ~ None        => UnlessNode(new UnlessStruct(unless_cond, unless_body))
    case unless_cond ~ unless_body ~ Some(_else) => UnlessNode(new UnlessStruct(unless_cond, unless_body, Some(_else)))
  }

  def whileBlock: Parser[WhileNode] = "while" ~> ("(" ~> expression <~ ")") ~ block ^^ {
    case cond ~ body => WhileNode(cond, body)
  }

  def untilBlock: Parser[WhileNode] = "until" ~> ("(" ~> expression <~ ")") ~ block ^^ {
    case cond ~ body => WhileNode(PrefixUnaryOpNode(cond, "!"), body)
  }

  def topicVariable = ("my".? ~> variableName) ^^ {
    case VariableNameNode(v) => VariableDeclarationNode(v, UndefLiteralNode())
  }

  def foreachBlock = "for(each)?".r ~> opt(topicVariable) ~ ("(" ~> expression <~ ")") ~ block ^^ {
    case Some(topic) ~ list ~ block => ForeachNode(topic, list, block)
    case None        ~ list ~ block => ForeachNode(VariableDeclarationNode("$_", UndefLiteralNode()), list, block)
  }

  def forBlock = "for" ~> (("(" ~> variableDeclaration) <~ ";") ~ (expression <~ ";") ~ (statement <~ ")") ~ block ^^ {
    case init ~ termCond ~ step ~ block => ForNode(init, termCond, step, block)
  }

  def tryBlock: Parser[TryNode] = ("try" ~> block) ~ rep(catchBlock) ~ rep(finallyBlock) ^^ {
    case a ~ b ~ c => TryNode(a, b, c)
  }

  def catchBlock: Parser[CatchNode] = ("catch" ~ "(") ~> namespacedIdentifier ~ variableName ~ (")" ~> block) ^^ {
    case a ~ VariableNameNode(b) ~ c => CatchNode(a, b, c)
  }

  def finallyBlock: Parser[FinallyNode] = "finally" ~> block ^^ FinallyNode

  /**
   *********************************************************************
   * Lastly, wrap it up with a general "statements"
   * production that encompasess much of the above
   *********************************************************************
   */  

  lazy val blockStatement: Parser[AST] = (
      ifElseBlock
    | unlessElseBlock
    | whileBlock
    | untilBlock
    | foreachBlock
    | forBlock
    | doBlock
    | tryBlock
  ) <~ opt(statementDelim)

  lazy val declarationStatement: Parser[AST] = (
      packageDecl
    | subroutineDecl
    | classDecl
  ) <~ opt(statementDelim)

  lazy val simpleStatement: Parser[AST] = (
      variableDeclaration
    | multiVariableDeclaration    
    | useStatement    
    | expression    
    | multiVariableAssignment
    | multiAttributeAssignment
  )

  /**
   * Statement modifiers
   */

  lazy val modifiedStatement: Parser[AST] = simpleStatement ~ "if|unless|for(each)?|while|until".r ~ expression ^^ {
    case stmt ~ "if"      ~ cond => IfNode(new IfStruct(cond, StatementsNode(List(stmt))))
    case stmt ~ "unless"  ~ cond => UnlessNode(new UnlessStruct(cond, StatementsNode(List(stmt))))
    case stmt ~ "foreach" ~ list => ForeachNode(VariableDeclarationNode("$_", UndefLiteralNode()), list, StatementsNode(List(stmt)))
    case stmt ~ "for"     ~ list => ForeachNode(VariableDeclarationNode("$_", UndefLiteralNode()), list, StatementsNode(List(stmt)))
    case stmt ~ "while"   ~ cond => WhileNode(cond, StatementsNode(List(stmt)))
    case stmt ~ "until"   ~ cond => WhileNode(PrefixUnaryOpNode(cond, "!"), StatementsNode(List(stmt)))
  }

  lazy val statement: Parser[AST] = ( modifiedStatement | simpleStatement )
  lazy val terminatedStatement: Parser[AST] = statement <~ statementDelim
}
