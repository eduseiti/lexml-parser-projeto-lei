package br.gov.lexml.parser.pl.linker

import org.apache.pekko.actor._

import scala.language.postfixOps
import br.gov.lexml.parser.pl.block._
import br.gov.lexml.parser.pl.rotulo._

import scala.xml._
import scala.concurrent.duration._
import org.apache.pekko.actor.SupervisorStrategy._
import grizzled.slf4j.Logger

import scala.concurrent.Await
import org.apache.pekko.pattern.ask
import org.apache.pekko.routing.SmallestMailboxPool

object Linker {

  val logger: Logger = Logger(this.getClass)

  val system: ActorSystem = ActorSystem("linker")
  
  private val strategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _ : java.io.IOException => Restart
    case _ : Exception => Escalate
  }

  val numLinkerInstances = sys.props.getOrElse("linker.numInstances","8").toInt
  val linkerProcessTimeout = sys.props.getOrElse("linker.timeoutSeconds","30").toInt

  private val linkerRouter = system.actorOf(Props[LinkerActor]().withRouter(SmallestMailboxPool(numLinkerInstances,
    supervisorStrategy = strategy)))
            
  
  // The external linker (`linkertool`) rejects several Portuguese-language
  // notations that are common in Brazilian legal text. We normalise text
  // nodes before sending them to the linker, then undo the changes that
  // would alter visible text in the output:
  //
  //   * `DD.MM.YYYY`  ↔  `DD/MM/YYYY`   (dot date → slash date, reversed
  //     after linking; pure recognition aid).
  //   * `no.`         →  `nº `          (typewriter ordinal "no." →
  //     proper ordinal sign; not reversed, since the source's "no."
  //     and "nº" mean the same thing and the typographically correct
  //     form is preferred).
  //   * `Dec.`        →  `Decreto`     (abbreviation expansion; not
  //     reversed — expanded form is the canonical citation form).
  //
  // Date rewrites preserve the visible text. Abbreviation expansions
  // intentionally change the visible text so the canonical/expanded
  // form is what appears in the LexML output.
  private val dotDateRe   = """\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b""".r
  private val slashDateRe = """\b(\d{1,2})/(\d{1,2})/(\d{4})\b""".r

  // "no." followed by whitespace and a digit → "nº " (handles "Lei no. 9.472").
  private val numAbbrRe = """\bno\.(\s+)(?=\d)""".r
  // Standalone "Dec." (followed by whitespace and a digit) → "Decreto".
  // Bounded by digit lookahead to avoid touching unrelated "dec." occurrences
  // (Portuguese for "ten" or end-of-sentence abbreviations).
  private val decAbbrRe = """\bDec\.(\s+)(?=\d)""".r

  private def rewriteTextNodes(ns : Seq[Node], f : String => String) : Seq[Node] = {
    def rewrite(n : Node) : Node = n match {
      case Text(t) => Text(f(t))
      case e : Elem => e.copy(child = e.child.map(rewrite))
      case other => other
    }
    ns.map(rewrite)
  }

  // Presentational inline elements whose tags often don't align with word
  // boundaries in source DOCX (e.g. `<i>capu</i>t`, `<i>caput</i><b>,</b>`).
  // When such a tag overlaps a citation, the external linker re-tokenises the
  // span and emits unbalanced markup that crashes the result parser. Flattening
  // these elements to their text content before linking sidesteps the bug while
  // still letting the citation be recognised. The inline styling is dropped from
  // the linked output, which is acceptable for reference text.
  private val inlineFormatTags = Set("i", "b", "em", "strong")

  private def flattenInlineFormat(ns : Seq[Node]) : Seq[Node] =
    ns.flatMap {
      case e : Elem if inlineFormatTags.contains(e.label) => flattenInlineFormat(e.child)
      case e : Elem => Seq(e.copy(child = flattenInlineFormat(e.child)))
      case other => Seq(other)
    }

  private def preprocess(s : String) : String = {
    val s1 = dotDateRe.replaceAllIn(s, "$1/$2/$3")
    val s2 = numAbbrRe.replaceAllIn(s1, "nº$1")
    val s3 = decAbbrRe.replaceAllIn(s2, "Decreto$1")
    s3
  }

  private def restoreDates(s : String) : String =
    slashDateRe.replaceAllIn(s, "$1.$2.$3")

  def findLinks(urnContexto : String, ns : Seq[Node]) : (List[String],List[Node]) = {
    logger.info(s"findLinks: urnContexto = $urnContexto, ns=$ns")
    import org.apache.pekko.util.Timeout
    implicit val timeout : Timeout = Timeout(linkerProcessTimeout.seconds)
    val nsRewritten = flattenInlineFormat(rewriteTextNodes(ns, preprocess))
    val msg = (urnContexto, nsRewritten)
    import system.dispatcher
    val f = (linkerRouter ? msg).mapTo[(List[Node],Set[String])] map {
      case (nl,links) => (links.toList,nl)
    }
    logger.info(s"findLinks: waiting for result....")
    val (links, nl) = Await.result(f,timeout.duration)
    val nlRestored = rewriteTextNodes(nl, restoreDates).toList
    val res = (links, nlRestored)
    logger.info(s"findLinks: result = $res")
    res
  }

  private def processaAlteracao(a: Alteracao, links: List[URN]): Alteracao = {
    val mr = MatchResult.fromAlteracao(a,links)
    val a1 = a copy (matches = Some(mr))
    mr.first.map(_.updateAlteracao(a1)).getOrElse(a1)
  }

  private def getLinks(d: Dispositivo): List[URN] = d.rotulo match {
    case _: RotuloArtigo => for {
      dd <- d.conteudo.toList.collect {
        case d: Dispositivo => d
      }
      l <- getLinks(dd)
    } yield l
    case _ => d.links.flatMap(URN.fromString)
  }

  def paraCadaAlteracao(bl: List[Block]): List[Block] = {
    def f(d: Dispositivo): Block => Block = {
      case a: Alteracao =>
        val links = getLinks(d)
        processaAlteracao(a, links)
      case dd: Dispositivo => dd.replaceChildren(dd.children.map(f(dd)))
      case x => x
    }
    def g(b: Block) = b match {
      case dd: Dispositivo => dd.replaceChildren(dd.children.map(f(dd)))
      case x => x
    }
    bl.map(g)
  }
}
