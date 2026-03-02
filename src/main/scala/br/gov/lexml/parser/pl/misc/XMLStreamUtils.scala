package br.gov.lexml.parser.pl.misc

import javax.xml.stream.events._

/** Marks a top-level body item extracted from word/document.xml in document order. */
sealed trait BodyItem
/** A &lt;w:p&gt; paragraph and all its descendant events. */
final case class ParItem(events: Seq[XMLEvent]) extends BodyItem
/** A &lt;w:tbl&gt; table and all its descendant events. */
final case class TblItem(events: Seq[XMLEvent]) extends BodyItem

object XMLStreamUtils {
  import CollectionUtils._

  private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

  private def collectElement(ns: String, label: String)(s: LazyList[XMLEvent]): LazyList[Seq[XMLEvent]] = {
    def isElemStart: XMLEvent => Boolean = {
      case ev: StartElement =>
        ev.getName.getNamespaceURI == ns && ev.getName.getLocalPart == label
      case _ => false
    }
    val sMarked = mapWith(s)((0, None: Option[Int]))({
      case ((level, None), ev: StartElement) if isElemStart(ev) => ((level + 1, Some(level + 1)), (false, ev))
      case ((level, elStart), ev: StartElement)                  => ((level + 1, elStart), (elStart.isDefined, ev))
      case ((level, Some(elLevel)), ev: EndElement) if level == elLevel => ((level - 1, None), (false, ev))
      case ((level, elStart), ev: EndElement)                    => ((level - 1, elStart), (elStart.isDefined, ev))
      case ((level, elStart), ev)                                => ((level, elStart), (elStart.isDefined, ev))
    })
    val segs = segmentBy(sMarked) {
      case ((v1, _), (v2, _)) => v1 != v2
    }
    segs.filter(_.head._1).map(_.map(_._2))
  }

  /** Existing public API — unchanged. */
  val collectPars: LazyList[XMLEvent] => LazyList[Seq[XMLEvent]] =
    collectElement(wNs, "p")(_)

  /**
   * Public helper: collect all occurrences of element (ns, label) from any
   * Iterable of events, preserving document order.
   * Used by DOCXReader to extract rows/cells from a table event sequence.
   */
  def collectElems(ns: String, label: String)(events: Iterable[XMLEvent]): LazyList[Seq[XMLEvent]] =
    collectElement(ns, label)(LazyList.from(events))

  /**
   * Collect &lt;w:p&gt; paragraphs and &lt;w:tbl&gt; tables from the document body
   * IN DOCUMENT ORDER as BodyItem values.
   *
   * The existing collectPars loses ordering when paragraphs and tables are
   * interleaved because it captures &lt;w:p&gt; at ANY nesting depth, including
   * paragraphs inside table cells. This single-pass function only captures
   * top-level &lt;w:p&gt; and &lt;w:tbl&gt; elements, preserving their original order.
   */
  def collectBodyItems(s: LazyList[XMLEvent]): LazyList[BodyItem] = {
    val targets: Set[String] = Set("p", "tbl")

    // State: (currentDepth, captureState)
    // captureState = Some((elementLabel, startDepth, accumulatedEvents))
    def go(
        events: LazyList[XMLEvent],
        depth: Int,
        capture: Option[(String, Int, Vector[XMLEvent])]
    ): LazyList[BodyItem] =
      events match {
        case LazyList() => LazyList()
        case ev #:: rest =>
          (ev, capture) match {

            // Start of a target element while not capturing → begin capture
            case (se: StartElement, None)
                if se.getName.getNamespaceURI == wNs &&
                   targets(se.getName.getLocalPart) =>
              go(rest, depth + 1, Some((se.getName.getLocalPart, depth + 1, Vector(se))))

            // Start of any element while capturing → accumulate
            case (se: StartElement, Some((lbl, sl, acc))) =>
              go(rest, depth + 1, Some((lbl, sl, acc :+ se)))

            // Start of non-target element while not capturing → track depth only
            case (_: StartElement, None) =>
              go(rest, depth + 1, None)

            // End element that closes the capture → emit BodyItem
            case (ee: EndElement, Some((lbl, sl, acc))) if depth == sl =>
              val item: BodyItem =
                if (lbl == "p") ParItem(acc :+ ee) else TblItem(acc :+ ee)
              item #:: go(rest, depth - 1, None)

            // End element inside capture → accumulate
            case (ee: EndElement, Some((lbl, sl, acc))) =>
              go(rest, depth - 1, Some((lbl, sl, acc :+ ee)))

            // End element outside capture → track depth only
            case (_: EndElement, None) =>
              go(rest, depth - 1, None)

            // Any other event (characters, etc.) while capturing → accumulate
            case (other, Some((lbl, sl, acc))) =>
              go(rest, depth, Some((lbl, sl, acc :+ other)))

            // Any other event outside capture → skip
            case (_, None) =>
              go(rest, depth, None)
          }
      }

    go(s, 0, None)
  }
}
