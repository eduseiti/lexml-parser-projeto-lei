package br.gov.lexml.parser.pl.docx

import javax.xml.stream.XMLInputFactory
import br.gov.lexml.parser.pl.misc.XMLStreamUtils._
import br.gov.lexml.parser.pl.misc.{BodyItem, ParItem, TblItem}

import scala.annotation.tailrec
import br.gov.lexml.parser.pl.misc.CollectionUtils._
import javax.xml.stream.events._


object DOCXReader {
  type Elem = (String,String)

  final case class TextStyle(bold : Boolean = false, italics : Boolean = false,
    subscript : Boolean = false, superscript : Boolean = false) {

    override def toString : String = {
      val label =
        (if (bold) {"B"} else "") +
        (if (italics) {"I"} else "") +
        (if (superscript) { "⌃"} else "") +
          (if (subscript) { "⌄"} else "")
      s"<$label>"
    }
  }

  val emptyStyle: TextStyle = TextStyle()

  abstract sealed class Segment {
    import scala.xml._
    def toXML : Seq[Node]
  }

  final case class TextSegment(style : TextStyle, text : String) extends Segment {
    import scala.xml._
    override def toXML: Seq[Node] =
      TextSegment.styles(style)(Text(text))

    override def toString : String = {
      val head = if(style != emptyStyle) { style.toString } else ""
      "〈" + head + text + "〉"
    }
  }

  object TextSegment {
    import scala.xml._
    def encloseIf(label : String)(cond : TextStyle => Boolean)(style : TextStyle)(nodes : Seq[Node]) : Seq[Node] =
      if(cond(style)) {
        val el = Elem(prefix = null,
                      label = label,
                      attributes = Null,
                      scope = TopScope,
                      minimizeEmpty = true,
                      child = nodes : _*)
        Seq(el)
      } else nodes

    val italicsIf = encloseIf("i")(_.italics) _
    val boldIf = encloseIf("b")(_.bold) _
    val supIf = encloseIf("sup")(_.superscript) _
    val subIf = encloseIf("sub")(_.subscript) _

    def styles(style : TextStyle)(nodes : Seq[Node]) : Seq[Node] =
      boldIf(style) {
        italicsIf(style) {
          supIf(style) {
            subIf(style) {
              nodes
            }
          }
        }
      }
  }

  case object Space extends Segment {
    import scala.xml._
    override def toXML = Seq(Text(" "))
    override def toString = "⎵"
  }

  case object Tab extends Segment {
    import scala.xml._
    override def toXML = Seq(Text(" "))
    override def toString = "⇥"
  }

  //From scalaz
  def intersperse[A](as: List[A], a: A): List[A] = {
    @tailrec
    def intersperse0(accum: List[A], rest: List[A]): List[A] = rest match {
      case Nil => accum
      case x :: Nil => x :: accum
      case h :: t => intersperse0(a :: h :: accum, t)
    }
    intersperse0(Nil, as).reverse
  }
  def breakText(style : TextStyle, text : String) : IndexedSeq[Segment] = {
    val txt = text.
                        replaceAll("""[\u00A0\s\n\r]"""," ").
                        replaceAll("""\s\s+"""," ")
    val hd = if(txt.startsWith(" ")) { IndexedSeq(Space) } else { IndexedSeq() }
    val tl = if(txt.endsWith(" ")) { IndexedSeq(Space) } else { IndexedSeq() }
    hd ++ intersperse(List(txt.split(" ").toIndexedSeq :_*).map(t => TextSegment(style,t)),Space).toIndexedSeq ++ tl
  }

  final case class XElem(ns : Option[String], label : String, attributes : Map[(String,String),String] = Map()) {
    override def toString = {
      val nsTxt = ns match {
        case Some(XElem.wNs) => "():"
        case Some(n) => s"($n):"
        case None => ""
      }
      nsTxt + label
    }
  }

  object XElem {
    def fromEvent(ev : StartElement): XElem = {
      val ns = Option(ev.getName.getNamespaceURI)
      import scala.jdk.CollectionConverters._
      val attrIt : Iterator[Attribute] = ev.getAttributes.asScala.collect { case x : Attribute => x }
      val attrSeq : Seq[Attribute] = attrIt.toSeq
      val attrPairs : Seq[((String,String),String)] = attrSeq.map { (att: javax.xml.stream.events.Attribute) =>
        ((att.getName.getNamespaceURI, att.getName.getLocalPart), att.getValue)
      }
      val attrs = attrPairs.toMap
      XElem(ns,ev.getName.getLocalPart,attrs)
    }

    val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

  }

  final case class Context(style : TextStyle = TextStyle(), stack : List[(XElem,TextStyle)] = List(), segments : IndexedSeq[Segment] = IndexedSeq()) {

    def enter(elem : XElem) : Context = copy(stack = (elem,style) :: stack)
    def leave(keepStyle : Option[TextStyle => TextStyle] = None) : Context = {
      val newStyle = keepStyle.map(f => f(style)).getOrElse(stack.head._2)
      copy(style = newStyle, stack = stack.tail)
    }

    def head: Option[XElem] = stack.headOption.map(_._1)
    def add(s : Segment*) : Context = copy(segments = segments ++ s)
    override def toString =
      s"⟦${ if (style != emptyStyle) { style.toString + ":" } else "" } ${segments.mkString("")} STACK: ${stack.map { case (e,style) => style.toString + e.toString }.mkString("⟪"," ","⟫")}⟧"
  }

  import XElem._
  private def processEvent(ctx : Context, event : XMLEvent) : Context = {
    (event,ctx.head) match {
      case (ev : StartElement,_) => ctx.enter(fromEvent(ev))
      case (ev : EndElement,Some(e)) if e.ns == Some(XElem.wNs) =>
        e.label match {
          case "i" => ctx.leave(Some(s => s.copy(italics = true)))
          case "b" => ctx.leave(Some(s => s.copy(bold = true)))
          case "pPr" | "rPr" => ctx.leave(Some(x => x))
          case "tab" => ctx.add(Tab)
          case "vertAlign" =>
            e.attributes.get((XElem.wNs,"val")) match {
              case Some("superscript") => ctx.leave(Some(s => s.copy(superscript = true)))
              case Some("subscript") => ctx.leave(Some(s => s.copy(subscript = true)))
              case _ => ctx.leave()
            }
          case _ => ctx.leave()
        }
      case (ev : Characters,_) =>
        val brokenText = breakText(ctx.style,ev.getData)
        ctx.add(brokenText:_*)
      case (ev : EntityReference,_) =>
        br.gov.lexml.parser.pl.util.Entities.entities.get(ev.getName).
          map(c => ctx.add(breakText(ctx.style,"" + c):_*)).getOrElse(ctx)
      case _ => ctx
    }
  }

  private def collectText(evs : Iterable[XMLEvent]) = {
    val segs1 = evs.foldLeft(Context())(processEvent).segments
    val segs2 = collapseBy(segs1) {
      case (Space,Space) => Space
      case (Space,Tab) => Tab
      case (Tab,Space) => Tab
      case (Tab,Tab) => Tab
      case (TextSegment(s1,t1),TextSegment(s2,t2))
        if s1 == s2 => TextSegment(s1,t1 ++ t2)
    }
    val segs3 = collapseBy3(segs2)({
      case (TextSegment(s1, t1), Space, TextSegment(s2, t2)) if s1 == s2 =>
        TextSegment(s1, t1 + " " + t2)
    })

    val segs4 = segs3.headOption match {
      case Some(x) if !x.isInstanceOf[TextSegment] => segs3.tail
      case _ => segs3
    }
    val segs5 = segs4.lastOption match {
      case Some(x) if !x.isInstanceOf[TextSegment] => segs4.init
      case _ => segs4
    }
    segs5
  }

  // ── Table conversion ────────────────────────────────────────────────────────

  /**
   * Convert a &lt;w:tbl&gt; event sequence into an XHTML &lt;table&gt; element.
   *
   * Supported:
   *   - Multiple rows and cells
   *   - colspan via &lt;w:gridSpan w:val="N"/&gt;
   *   - Cell paragraph content reuses collectText
   *   - Vertical-merge continuation cells are dropped (Phase 2 adds rowspan)
   *
   * The generated &lt;table&gt; has no id attribute — LexmlRenderer adds it.
   */
  private def convertTable(tblEvents: Seq[XMLEvent]): scala.xml.Elem = {
    import scala.xml.{Elem => ScalaElem, MetaData, Node, Null => XmlNull,
                      Text, TopScope, UnprefixedAttribute}
    import scala.jdk.CollectionConverters._

    /** Return the value of attrLocalName on the first StartElement matching
     *  elemLabel within evs, or None. */
    def firstAttr(evs: Iterable[XMLEvent], elemLabel: String, attrLocalName: String): Option[String] =
      evs.collectFirst {
        case se: StartElement if se.getName.getLocalPart == elemLabel =>
          se.getAttributes.asScala
            .collect { case a: Attribute => a }
            .find(_.getName.getLocalPart == attrLocalName)
            .map(_.getValue)
      }.flatten

    /** True when the cell is a vMerge continuation (should be skipped). */
    def isVMergeContinuation(tcPrEvs: Iterable[XMLEvent]): Boolean =
      tcPrEvs.exists {
        case se: StartElement if se.getName.getLocalPart == "vMerge" =>
          val valOpt = se.getAttributes.asScala
            .collect { case a: Attribute => a }
            .find(_.getName.getLocalPart == "val")
            .map(_.getValue)
          // no val attribute, or any value other than "restart" = continuation
          valOpt.forall(_ != "restart")
        case _ => false
      }

    val rowEventSeqs = collectElems(XElem.wNs, "tr")(tblEvents)

    val trElems: Seq[ScalaElem] = rowEventSeqs.flatMap { rowEvs =>

      val cellEventSeqs = collectElems(XElem.wNs, "tc")(rowEvs)

      val tdElems: Seq[ScalaElem] = cellEventSeqs.flatMap { cellEvs =>

        val tcPrEvs: Iterable[XMLEvent] =
          collectElems(XElem.wNs, "tcPr")(cellEvs).headOption.getOrElse(Seq.empty)

        if (isVMergeContinuation(tcPrEvs)) {
          // Phase 1: drop vertical-merge continuation cells
          None
        } else {

          val colspan: Int =
            firstAttr(tcPrEvs, "gridSpan", "val").flatMap(_.toIntOption).getOrElse(1)

          // Collect all <w:p> inside the cell; concatenate their inline content
          val parContents: Seq[Seq[Node]] =
            collectElems(XElem.wNs, "p")(cellEvs)
              .map(pEvs => collectText(pEvs).flatMap(_.toXML).toSeq)
              .filter(_.nonEmpty)
              .toSeq

          val cellNodes: Seq[Node] = parContents match {
            case Seq()     => Seq.empty
            case Seq(only) => only
            // Multiple paragraphs in a cell: separate with a space
            case parts     => parts.reduce((a, b) => a ++ Seq(Text(" ")) ++ b)
          }

          val colspanAttr: MetaData =
            if (colspan > 1) new UnprefixedAttribute("colspan", colspan.toString, XmlNull)
            else XmlNull

          Some(ScalaElem(null, "td", colspanAttr, TopScope, minimizeEmpty = true, cellNodes: _*))
        }
      }.toSeq

      if (tdElems.isEmpty) None
      else Some(ScalaElem(null, "tr", XmlNull, TopScope, minimizeEmpty = true, tdElems: _*))

    }.toSeq

    // id attribute is added later by LexmlRenderer
    ScalaElem(null, "table", XmlNull, TopScope, minimizeEmpty = true, trElems: _*)
  }

  // ── Entry point ─────────────────────────────────────────────────────────────

  import java.io.InputStream
  import java.util.zip._

  def readDOCX(s: InputStream): Option[scala.xml.Elem] = {
    val zis = new ZipInputStream(s)
    var entry = zis.getNextEntry
    while (entry != null && entry.getName != "word/document.xml") {
      entry = zis.getNextEntry
    }
    if (entry != null) {
      val reader = XMLInputFactory.newFactory().createXMLEventReader(zis, "UTF-8")
      import scala.jdk.CollectionConverters._
      val events = LazyList.from(reader.asScala.collect { case e: XMLEvent => e })

      // Collect paragraphs AND tables in document order.
      // Previously collectPars(events) captured <w:p> at any nesting depth,
      // including paragraphs inside table cells, causing parse failures on
      // documents that contain tables.
      val bodyItems: LazyList[BodyItem] = collectBodyItems(events)

      val nodes: LazyList[scala.xml.Elem] = bodyItems.flatMap {
        case ParItem(pEvs) =>
          val segs = collectText(pEvs)
          if (segs.isEmpty) None
          else Some(<p>{ segs.flatMap(_.toXML) }</p>)

        case TblItem(tblEvs) =>
          Some(convertTable(tblEvs))
      }

      Some(<html><body>{ nodes }</body></html>)
    } else {
      None
    }
  }
}
