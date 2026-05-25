package br.gov.lexml.parser.pl

import scala.language.postfixOps
import scala.util.matching.Regex
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.NodeSeq
import scala.xml.Text
import grizzled.slf4j.Logging
import ProjetoLei.existsAnyThat
import ProjetoLei.existsAnyThatP
import br.gov.lexml.parser.pl.block.Alteracao
import br.gov.lexml.parser.pl.block.Block
import br.gov.lexml.parser.pl.block.Dispositivo
import br.gov.lexml.parser.pl.block.Image
import br.gov.lexml.parser.pl.block.OL
import br.gov.lexml.parser.pl.block.Omissis
import br.gov.lexml.parser.pl.block.Paragraph
import br.gov.lexml.parser.pl.block.Table
import br.gov.lexml.parser.pl.errors.ArticulacaoNaoIdentificada
import br.gov.lexml.parser.pl.errors.EmentaAusente
import br.gov.lexml.parser.pl.errors.EpigrafeAusente
import br.gov.lexml.parser.pl.errors.ErroSistema
import br.gov.lexml.parser.pl.errors.ParseException
import br.gov.lexml.parser.pl.errors.ParseProblem
import br.gov.lexml.parser.pl.linker.Linker.findLinks
import br.gov.lexml.parser.pl.linker.Linker
import br.gov.lexml.parser.pl.metadado.Metadado
import br.gov.lexml.parser.pl.output.LexmlRenderer
import br.gov.lexml.parser.pl.rotulo.RotuloPena
import br.gov.lexml.parser.pl.rotulo.niveis
import br.gov.lexml.parser.pl.rotulo.rotuloParser
import br.gov.lexml.parser.pl.text.normalizer
import br.gov.lexml.parser.pl.validation.Validation
import br.gov.lexml.parser.pl.profile.DocumentProfile
import org.apache.commons.io.FileUtils

import java.io.File
import scala.annotation.unused

object Caracteristicas {
  val POSSUI_TABELA_ARTICULACAO = "possui tabela na articulacao"
  val POSSUI_ALTERACAO = "possui alteracao"
  val POSSUI_TITULO = "possui titulo"
  val POSSUI_PENA = "possui pena"
  val POSSUI_IMAGEM = "possui imagem"
}

/**
 * An annex (Anexo) attached to a Norma. The LexML schema requires anexos to be
 * standalone <LexML><Anexo>…</Anexo></LexML> documents, with the parent Norma
 * holding only <ReferenciaAnexo URN="…"/> pointers. So each Anexo carries the
 * URN fragment used to mint its full URN and to reference it from the Norma.
 *
 *  - num       — 1-based ordinal (used to build the URN suffix and stable ids)
 *  - titulo    — first paragraph of the explicit "ANEXO I" heading, when present
 *  - blocks    — anexo body (paragraphs, tables, optionally Dispositivos)
 *  - implicit_ — true when promoted by the implicit-anexo heuristic (no "ANEXO" header)
 */
case class Anexo(
  num: Int,
  titulo: Option[Paragraph],
  blocks: List[Block],
  implicito: Boolean = false) {
  def urnFragment: String = s"anexo$num"
}

case class ProjetoLei(
  metadado: Metadado, preEpigrafe: List[Block], epigrafe: Block, ementa: Option[Block],
  preambulo: List[Paragraph], articulacao: List[Block],
  localData: List[Paragraph] = Nil,
  assinaturas: List[Paragraph] = Nil,
  anexos: List[Anexo] = Nil,
  otherCaracteristicas: Map[String, Boolean] = Map()) extends Logging {
  import ProjetoLei._
  lazy val toNodeSeq: NodeSeq =
    <projetolei>
      <preEpigrafe>{ NodeSeq fromSeq (preEpigrafe.flatMap(_.toNodeSeq)) }</preEpigrafe>
			{ementa.map(x => <ementa>{x.toNodeSeq}</ementa>).getOrElse(NodeSeq.Empty) }
      <preambulo>{ NodeSeq fromSeq preambulo.flatMap(_.toNodeSeq) }</preambulo>
      <articulacao>{ NodeSeq fromSeq (articulacao.flatMap(_.toNodeSeq)) }</articulacao>
    </projetolei>

  lazy val remakeEpigrafe: ProjetoLei = {
    // Try to parse ID from original epigrafe if metadata doesn't have one
    val updatedMetadado = metadado.id match {
      case None =>
        ProjetoLei.parseEpigrafeId(epigrafe) match {
          case Some(parsedId) => metadado.copy(id = Some(parsedId))
          case None => metadado
        }
      case Some(_) => metadado
    }

    this.copy(
      metadado = updatedMetadado,
      epigrafe = Paragraph(Text(updatedMetadado.epigrafePadrao))
    )
  }
  lazy val dispositivoCount = {
    def count(b: Block): Int = b match {
      case d: Dispositivo => 1 + d.subDispositivos.map(count(_)).sum
      case o: Omissis => 1
      case a: Alteracao => 1 + a.blocks.map(count(_)).sum
      case _ => 0
    }
    articulacao.map(count(_)).sum
  }

  import Caracteristicas._
  lazy val caracteristicas: Map[String, Boolean] = (Map(
    POSSUI_TABELA_ARTICULACAO -> existsAnyThat(articulacao, _.isInstanceOf[Table]),
    POSSUI_ALTERACAO -> existsAnyThat(articulacao, _.isInstanceOf[Alteracao]),
    //"possui imagem" -> existsAnyThat(articulacao, _ == Image), 
    POSSUI_TITULO -> existsAnyThatP(articulacao, { case d: Dispositivo => d.titulo.isDefined }),
    POSSUI_PENA -> existsAnyThatP(articulacao, { case d: Dispositivo => d.rotulo == RotuloPena }))
    ++ otherCaracteristicas)

  lazy val asXML: NodeSeq = LexmlRenderer.render(this)
}

//class ParserException(msg: String) extends Exception

abstract sealed class Marcador extends Ordered[Marcador] with Equals {
  val id: Int
  final override def compare(that: Marcador) = id - that.id
  final override def equals(other: Any): Boolean = other match {
    case that: Marcador => (that canEqual this) && (that.id == this.id)
    case _ => false
  }
  final override def hashCode() = id
}

case object LocalData extends Marcador { val id = 0 }
case object Justificacao extends Marcador { val id = 1 }
case object Anexo extends Marcador { val id = 2 }
case object Legislacao extends Marcador { val id = 3 }
case object Assinatura extends Marcador { val id = 4 }
case object Articulacao extends Marcador { val id = 5 }

/**
 * Result of partitioning the post-preambulo blocks: a sequence of
 * (marker, blocks) chunks in document order. Unlike a Map this keeps multiple
 * Anexo buckets distinct (a doc may have ANEXO I, ANEXO II, …).
 */
case class MarcadoresResult(chunks: List[(Marcador, List[Block])]) {
  /** All blocks for a given marker, concatenated in document order. */
  def all(m: Marcador): List[Block] =
    chunks.collect { case (mm, bs) if mm == m => bs }.flatten

  /** Anexo buckets in document order. */
  def anexos: List[List[Block]] =
    chunks.collect { case (Anexo, bs) => bs }

  def withChunks(c: List[(Marcador, List[Block])]): MarcadoresResult =
    MarcadoresResult(c)
}

case class Marcadores(profile: DocumentProfile,
  localData: Boolean = false, justificacao: Boolean = false,
  anexoCount: Int = 0, legislacao: Boolean = false,
  assinatura: Boolean = false) {

  def oneOf(r: List[Regex]) = (b: Block) => b match {
    case p: Paragraph => r.find(_.findFirstIn(normalizer.normalize(p.text)).isDefined).map(_ => p)
    case _ => None
  }

  def matchesOneOf(r: List[Regex]) = oneOf(r).andThen(_.isDefined)

  val reMarcadores: Map[Marcador, Block => Boolean] = Map[Marcador, List[Regex]](
    LocalData -> profile.regexLocalData,
    Justificacao -> profile.regexJustificativa,
    Anexo -> profile.regexAnexos,
    Legislacao -> profile.regexLegislacaoCitada,
    Assinatura -> profile.regexAssinatura).view.mapValues(matchesOneOf).toMap

  def reconheceMarcador(b: Block): Option[Marcador] =
    reMarcadores.toList collectFirst { case (n, f) if f(b) => n }

  // For Anexo we always allow re-entry (each "ANEXO N" header opens a new
  // bucket). Other markers stay one-shot, matching the original behaviour.
  def reconhece(b: Block): Option[(Marcadores, Marcador)] = {
    reconheceMarcador(b) match {
      case None => None
      case Some(m) => m match {
        case LocalData => if (localData) None else Some(copy(localData = true), m)
        case Justificacao => if (justificacao) None else Some(copy(justificacao = true), m)
        case Anexo => Some(copy(anexoCount = anexoCount + 1), m)
        case Legislacao => if (legislacao) None else Some(copy(legislacao = true), m)
        case Assinatura => if (assinatura) None else Some(copy(assinatura = true), m)
        case Articulacao => throw new RuntimeException("reconheceMarcador nunca pode reconhecer a articulação")
      }
    }
  }

  def span(bl: List[Block]): MarcadoresResult = {
    // The block that triggers a marker switch becomes the first block of
    // the new bucket (not the closing one of the previous). This is needed
    // so that lines like "Brasília, …" (LocalData) and "ANEXO I" (Anexo)
    // survive into their own bucket where downstream rendering can use them.
    def seek(ms: Marcadores,
      m: Marcador,
      accum: List[Block],
      acc: List[(Marcador, List[Block])],
      blocks: List[Block]): List[(Marcador, List[Block])] =
      blocks match {
        case Nil => ((m, accum.reverse) :: acc).reverse
        case (b :: bl) => (ms.reconhece(b)) match {
          case None => seek(ms, m, b :: accum, acc, bl)
          case Some((ms2, m2)) =>
            seek(ms2, m2, List(b), (m, accum.reverse) :: acc, bl)
        }
      }
    MarcadoresResult(seek(this, Articulacao, List(), List(), bl))
  }

  val finished: Boolean = (localData || assinatura) && justificacao && anexoCount > 0 && legislacao
}

class ProjetoLeiParser(profile: DocumentProfile) extends Logging {

  import ProjetoLeiParser._


  private def spanEpigrafe(bl: List[Block]): Option[(List[Block], Block, List[Block])] = {

    // System.err.println(s"[DEBUG spanEpigrafe] block: '${bl}'")
    // System.err.println(s"[DEBUG spanEpigrafe] regexEpigrafe1: '${profile.regexEpigrafe1}'")

    val (pre, bl1) = bl.span(doesNotMatchAnyOf(profile.regexEpigrafe1))

    // System.err.println(s"[DEBUG spanEpigrafe] pre: '${pre}'")
    // System.err.println(s"[DEBUG spanEpigrafe] bl1: '${bl1}'")

    val pre2 = pre.filter(!isEmptyPar(_))

    // System.err.println(s"[DEBUG spanEpigrafe] pre2: '${pre2}'")

    val (epi, pos) = bl1.span(matchesOneOf(profile.regexEpigrafe ++ profile.regexEpigrafe1))

    // System.err.println(s"[DEBUG spanEpigrafe] epi: '${epi}'")
    // System.err.println(s"[DEBUG spanEpigrafe] pos: '${pos}'")


    val pos2 = pos.dropWhile(b => matchesOneOf(profile.regexPosEpigrafe)(b) || isEmptyPar(b))
    val epi2 = epi collect { case p: Paragraph => p }
    epi2 match {
      case Nil => None
      case _ =>
        val npNodes = epi2.headOption.toList.flatMap(_.nodes) ++
          epi2.tail.flatMap(Text(" ") :: _.nodes.toList)
        val np = Paragraph(npNodes)
        Some((pre2, np, pos2))
    }
  }

  // True when a block looks like the start of the articulacao — i.e. its
  // rotulo nivel sits at or above the maximum nivel accepted at the document
  // root. Shared by reconhecePreambulo and unwrapLeadingLayoutTables so the
  // "before articulacao" boundary is computed identically in both places.
  private val isArticulacaoStart: Block => Boolean = {
    case p: Paragraph => rotuloParser.parseRotulo(p.text) match {
      case Some((rotulo, _)) => rotulo.nivel <= niveis.nivel_maximo_aceito_na_raiz
      case None => false
    }
    case _ => false
  }

  private def reconhecePreambulo(bl: List[Block]): (List[Block], List[Paragraph], List[Block]) = {
    val isPreambulo = matchesOneOf(profile.regexPreambulo)
    val isPosEpigrafe = matchesOneOf(profile.regexPosEpigrafe)
    val (prePreambulo, preAmbuloAndPos) = bl.span(x => !isPreambulo(x) && !isArticulacaoStart(x))

    // System.err.println(s"[DEBUG reconhecePreambulo] prePreambulo: '${prePreambulo}'")
    // System.err.println(s"[DEBUG reconhecePreambulo] preAmbuloAndPos: '${preAmbuloAndPos}'")

    val (preAmbulo1, posPreambulo) = preAmbuloAndPos.span(!isArticulacaoStart(_))

    // System.err.println(s"\n[DEBUG reconhecePreambulo] preAmbulo1: '${preAmbulo1}'")
    // System.err.println(s"\n[DEBUG reconhecePreambulo] posPreambulo: '${posPreambulo}'")

    // val preAmbulo = preAmbulo1.filter({ case p: Paragraph => !isPosEpigrafe(p); case _ => true })
    val (preAmbulo, posPreambulo1) = preAmbulo1.span(!isPosEpigrafe(_))

    System.err.println(s"\n[DEBUG reconhecePreambulo] preAmbulo: '${preAmbulo}'")
    System.err.println(s"\n[DEBUG reconhecePreambulo] posPreambulo1 ++ posPreambulo: '${posPreambulo1 ++ posPreambulo}'")

    (prePreambulo, preAmbulo.collect { case p: Paragraph => p }, posPreambulo1 ++ posPreambulo)
  }


  
  def parseArticulacao(bl: List[Block], useLinker: Boolean = true, urnContexto : String): List[Block] = {
    val articulacao0 = {
      import java.text.Normalizer
      import Normalizer.Form.NFC
      def norm(x : String) =
        Normalizer.normalize(x,NFC)
      import scala.xml._
      def normalize(n : Node) : Node = n match {
        case x : Text => Text(norm(x.text))
        case e : Elem => e.copy(child = e.child.map(normalize))
        case x => x
      }
      bl.map {
        case p: Paragraph => p.copy(nodes = p.nodes.map(normalize))
        case x => x
      }
    }
    //Trim paragraphs
    val articulacao1 = articulacao0.map(trimParagraphs)
    //printArticulacao(articulacao1,1)
    val articulacao2 = trimEmptyPars(articulacao1)
    //printArticulacao(articulacao2,2)
    val articulacao3 = Block.reconheceAlteracoes(articulacao2)
    //printArticulacao(articulacao3,3)
    val articulacao4 = Block.reconheceDispositivos(articulacao3)
    //printArticulacao(articulacao4,4)      
    val articulacao5 = Block.reconheceOmissis(articulacao4)
    //printArticulacao(articulacao5,5)
    val articulacao6 = Block.identificaTextosAgregadores(articulacao5)
    //printArticulacao(articulacao6,6)
    val articulacao7 = Block.identificaTitulos(articulacao6)
    //printArticulacao(articulacao7,7)
    val articulacao7a = limpaParagrafosVazios(articulacao7)
    //printArticulacao(articulacao7a,7)
    val articulacao8 = Block.organizaDispositivos(articulacao7a)
    //printArticulacao(articulacao8,8)
    val articulacao8_1 = Block.numeraDispositivosGenericos(articulacao8)
    val articulacao8_2 = Block.corrigeRotuloParte(articulacao8_1)
    val articulacao8_3 = Block.corrigeRotuloLivro(articulacao8_2)
    //val articulacao9 = Block.reconheceOmissisVazio(articulacao8)
    val articulacao9 = Block.limpaParagrafosVazios(articulacao8_3)
    //printArticulacao(articulacao9,9)
    val articulacao9_1 = Block.pushLastOmissis(articulacao9)
    //val articulacao9 = articulacao8
    //printArticulacao(articulacao9,9)
    val articulacao10 = Block.numeraAlteracoes(articulacao9_1)
    //printArticulacao(articulacao10,10)
    val articulacao11 = Block.identificaPaths(articulacao10)
    //printArticulacao(articulacao11,11)
    val articulacao11_1 = Block.numeraDispsGenericos(articulacao11)
    if (useLinker) {
      val articulacao12 = reconheceLinks(articulacao11_1, urnContexto)
      //printArticulacao(articulacao12,12)
      val articulacao13 = Linker.paraCadaAlteracao(articulacao12)
      //printArticulacao(articulacao13,13)
      articulacao13
    } else {
      articulacao11_1
    }
  }

  /**
   * Convert each Anexo bucket of Blocks into an Anexo case-class instance.
   * If the first paragraph matches "ANEXO ..." it's stored as titulo and
   * stripped from the body. The body is then routed through parseArticulacao
   * so hierarchical legal structures (Capitulo, Secao, Artigo, ...) inside
   * the annex are recognised — the schema (lexml-base.xsd) permits an annex
   * to be a fully articulated document via DocumentoArticulado/Articulacao.
   * If the body has no recognised dispositivos parseArticulacao returns it
   * as Paragraphs/Tables, and the renderer falls back to DocumentoGenerico.
   */
  def buildAnexos(buckets: List[List[Block]], urnContexto: String): List[Anexo] = {
    buckets.zipWithIndex.flatMap { case (raw, idx) =>
      val trimmed = trimEmptyPars(raw)
      if (trimmed.isEmpty) None
      else {
        val (titulo, body0) = trimmed match {
          case (p: Paragraph) :: tail
              if anexoHeadRe.findFirstIn(normalizer.normalize(p.text.trim.toLowerCase)).isDefined =>
            (Some(p), tail)
          case _ => (None, trimmed)
        }
        val bodyTrimmed = trimEmptyPars(body0)
        // parseArticulacao already runs reconheceLinks + Linker.paraCadaAlteracao,
        // so don't apply reconheceLinks again on the result.
        val body = parseArticulacao(bodyTrimmed, urnContexto = urnContexto)
        Some(Anexo(num = idx + 1, titulo = titulo, blocks = body, implicito = titulo.isEmpty))
      }
    }
  }

  // Casa Civil DOCX/HTML originals frequently wrap the heading area (image
  // banner, epigrafe, ementa) in layout tables. Those tables become Table
  // blocks and trip the ementa validator (which requires Paragraph blocks).
  // Flatten any Table that appears before the first articulacao block —
  // tables inside the articulacao are left untouched so real data tables
  // continue to render as <table> in the output (see commits 7c9a9a4 / d8f0108).
  private def unwrapLeadingLayoutTables(blocks: List[Block]): List[Block] = {
    val (head, tail) = blocks.span(b => !isArticulacaoStart(b))
    val flattenedHead = head.flatMap {
      case Table(elem) =>
        // Each cell's children are inline content (text, <span>, …), not
        // wrapped in <p>. Wrap them so Block.fromNodes turns each cell into
        // a Paragraph.
        val cellParagraphs = (elem \ "tr" \ "td").toList.flatMap { td =>
          val children = td.child.toList
          if (children.isEmpty) Nil
          else List(<p>{children}</p>)
        }
        Block.fromNodes(cellParagraphs)
      case b => List(b)
    }
    flattenedHead ++ tail
  }

  def fromBlocks(metadado: Metadado, blocks: List[Block]): (Option[ProjetoLei], List[ParseProblem]) = {
    try {
      val unwrappedBlocks = unwrapLeadingLayoutTables(blocks)
      val (preEpigrafe, epigrafe, posEpigrafe) = {
        if (profile.regexEpigrafe.isEmpty) {
          (List(), Paragraph(List()), unwrappedBlocks)
        } else {
          spanEpigrafe(unwrappedBlocks) match {
            case None if !profile.epigrafeObrigatoria => (List(), Paragraph(List()), unwrappedBlocks)
            case Some(p @ (pre, _, _)) if profile.preEpigrafePermitida || pre.isEmpty => p
            case _ => throw ParseException(EpigrafeAusente)
          }
        }
      }
      val (ementa1Raw, preambulo, posPreambulo) = reconhecePreambulo(posEpigrafe)
      // Drop pos-epígrafe annotations (e.g. website-export "Publicado:",
      // "Prazos ...", "Observação ...") anywhere in the ementa region, not
      // just leading ones. spanEpigrafe only strips them from the *front* of
      // the post-epígrafe blocks via dropWhile; an annotation that follows the
      // real ementa sentence would otherwise be joined into the ementa.
      val isPosEpigrafe = matchesOneOf(profile.regexPosEpigrafe)
      val ementa1 = ementa1Raw.filterNot(isPosEpigrafe)
      val ementa2 = trimEmptyPars(ementa1)
      val ementa = if (
            ementa2.isEmpty ||
            ementa2.exists(isEmptyPar) ||
            ementa2.exists(!isParagraph(_))) {
              if (profile.ementaAusente) { None } else { 
                throw ParseException(EmentaAusente)
              }
          } else {
            Some(Block.joinParagraphs(ementa2).head)
          }
      
      val ms = Marcadores(profile)
      val elementos0 = ms.span(posPreambulo)

      System.err.println(s"\n[DEBUG parseArticulacao] elementos: '${elementos0}'")

      if (!elementos0.chunks.exists(_._1 == Articulacao)) {
        throw ParseException(ArticulacaoNaoIdentificada)
      }

      // Promote trailing post-signature content into an implicit Anexo when
      // there is no explicit "ANEXO" heading. See implicitAnexoPromotion docs.
      val elementos = implicitAnexoPromotion(elementos0, profile)

      val urnContexto = metadado.urnContextoLinker

      val articulacao1 = elementos.all(Articulacao) ++ trailingTables(elementos)
      val articulacao = parseArticulacao(articulacao1, urnContexto = urnContexto)

      val anexoBlocksAll = elementos.anexos.flatMap(_.collect { case b => b })
      val possuiImagem = (preEpigrafe ++ List(epigrafe) ++ preambulo ++ articulacao1 ++ anexoBlocksAll).exists({
        case p: Paragraph => (p.nodes \\ "img").nonEmpty
        case Image => true
        case _ => false
      })

      val localDataPars = elementos.all(LocalData).collect { case p: Paragraph if p.text.nonEmpty => p }
      val assinaturaPars = elementos.all(Assinatura).collect { case p: Paragraph if p.text.nonEmpty => p }

      val anexos = buildAnexos(elementos.anexos, urnContexto)

      import Caracteristicas._

      val otherCaracteristicas = Map[String, Boolean](
        POSSUI_IMAGEM -> possuiImagem)

      val pl = ProjetoLei(
        metadado = metadado,
        preEpigrafe = preEpigrafe,
        epigrafe = epigrafe,
        ementa = ementa.map(x => reconheceLinks(x,urnContexto)),
        preambulo = preambulo,
        articulacao = articulacao,
        localData = localDataPars,
        assinaturas = assinaturaPars,
        anexos = anexos,
        otherCaracteristicas = otherCaracteristicas)

      val falhas = try {
        new Validation().validaEstrutura(articulacao)
      } catch {
	      case e: ParseException => e.errors.to(Set)
        case e: Exception => Set(ErroSistema(e))
      }
      (Some(pl), falhas.toList)
    } catch {
      case e: ParseException => (None, e.errors.toList)
      case e: Exception => (None, List(ErroSistema(e)))
    }
  }
}

object ProjetoLeiParser {
  private def isEmptyPar(b: Block): Boolean = b match {
    case Paragraph(_, "") => true
    case _ => false
  }

  private def isParagraph(b: Block): Boolean = b match {
    case Paragraph(_, _) => true
    case _ => false
  }

  private def trimEmptyPars(bl: List[Block]): List[Block] = {
    bl.dropWhile(isEmptyPar).reverse.dropWhile(isEmptyPar).reverse
  }

  // Tables stuck under non-Articulacao non-Anexo tail markers (LocalData /
  // Justificacao / Legislacao / Assinatura) would otherwise be silently
  // dropped. Salvage them so parseArticulacao can attach them to the last
  // article via spanNivel. Anexo tables stay in the Anexo bucket.
  private val tailMarkers: List[Marcador] =
    List(LocalData, Justificacao, Legislacao, Assinatura)

  private def trailingTables(elementos: MarcadoresResult): List[Block] =
    tailMarkers.flatMap(elementos.all).collect { case t: Table => t }

  // ----------------------------- Implicit Anexo --------------------------------
  //
  // Some legal docs end with extra material (tables, heading paragraphs) that
  // sits AFTER the signing block but lacks a literal "ANEXO" header. Without
  // help that material lands in the LocalData/Assinatura bucket and gets
  // dropped. We promote it to an implicit Anexo when:
  //  - no explicit Anexo bucket exists, AND
  //  - the trailing region (after the last name-like signing line) contains
  //    at least one Table, OL, or an administrative-heading paragraph
  //    (all-caps + administrative keyword, or followed by a Table).

  // Words that strongly suggest the line is anexo content rather than a
  // person's name. All-caps headings carrying any of these are NOT treated
  // as signing-tail lines.
  private val anexoHeadingKeywordRe =
    """(?i)\b(tabela|anexo|conselho|departamento|comiss[aã]o|secretaria|minist[eé]rio|presid[eê]ncia|gabinete|tribunal|junta|cargos?|provimento|fundo|programa|or[gç]amento|crit[eé]rios?|defini[cç]oes|disposi[cç][oõ]es|relac[aã]o|listagem|itens?|grupo|categoria)\b""".r

  private val rejectImplicitAnexoPatterns: List[Regex] = List(
    "^publicado em"r,
    """^p\.\s*\d"""r,
    "^d\\.o\\.u"r
  )

  /** Strict person-name detector: 1-6 short words, leading caps required,
   *  no digits, no administrative keywords. Used as part of the signing-tail
   *  scan that decides where the implicit anexo starts.
   *  Operates on unormalizedText so case is preserved. */
  private def looksLikePersonName(p: Paragraph): Boolean = {
    val raw = p.unormalizedText.trim
    if (raw.length < 2 || raw.length > 70) return false
    if (raw.exists(_.isDigit)) return false
    if (anexoHeadingKeywordRe.findFirstIn(raw).isDefined) return false
    val words = raw.split("\\s+").filter(_.nonEmpty)
    if (words.length < 1 || words.length > 6) return false
    val firstChar = raw.head
    if (!firstChar.isUpper) return false
    words.forall { w =>
      val core = w.stripSuffix(".")
      core.length <= 25 && core.nonEmpty && core.forall(c => c.isLetter || c == '-' || c == '\'')
    }
  }

  /** Heading-like = all-letters-uppercase, length ≥ 3, no terminal punctuation,
   *  contains at least one administrative-style keyword.
   *  Operates on unormalizedText so case is preserved. */
  private def isAdministrativeHeading(p: Paragraph): Boolean = {
    val t = p.unormalizedText.trim
    if (t.length < 3) return false
    if (t.endsWith(".") || t.endsWith(":") || t.endsWith(";")) return false
    val letters = t.filter(_.isLetter)
    if (letters.isEmpty || !letters.forall(_.isUpper)) return false
    val nt = normalizer.normalize(t.toLowerCase)
    if (rejectImplicitAnexoPatterns.exists(_.findFirstIn(nt).isDefined)) return false
    anexoHeadingKeywordRe.findFirstIn(t).isDefined
  }

  private def looksLikeAnexoContent(blocks: List[Block]): Boolean = blocks.exists {
    case _: Table => true
    case _: OL => true
    case p: Paragraph => isAdministrativeHeading(p)
    case _ => false
  }

  /** Walks blocks from index 0 forward and returns the index AFTER the
   *  last contiguous signing block. The signing region begins with a
   *  LocalData/Assinatura match (e.g. "Brasília, ...") and may include
   *  subsequent person-name paragraphs. The first paragraph that is neither
   *  a signing-regex hit nor a person name (or any Table/OL) ends the
   *  signing region. */
  private def signingRegionEnd(blocks: List[Block], profile: DocumentProfile): Int = {
    val isSig = (p: Paragraph) => {
      val n = normalizer.normalize(p.text.trim.toLowerCase)
      profile.regexLocalData.exists(_.findFirstIn(n).isDefined) ||
        profile.regexAssinatura.exists(_.findFirstIn(n).isDefined)
    }
    var i = 0
    var lastSigIdx = -1
    while (i < blocks.length) {
      blocks(i) match {
        case p: Paragraph if p.text.trim.isEmpty =>
          // empty paragraph: keep scanning, don't move lastSigIdx
        case p: Paragraph if isSig(p) || looksLikePersonName(p) =>
          lastSigIdx = i
        case _: Paragraph => return lastSigIdx + 1
        case _: Table | _: OL => return lastSigIdx + 1
        case _ => return lastSigIdx + 1
      }
      i += 1
    }
    lastSigIdx + 1
  }

  def implicitAnexoPromotion(elementos: MarcadoresResult): MarcadoresResult =
    implicitAnexoPromotion(elementos, profile = null)

  def implicitAnexoPromotion(elementos: MarcadoresResult, profile: DocumentProfile): MarcadoresResult = {
    val hasExplicitAnexo = elementos.chunks.exists(_._1 == Anexo)
    if (hasExplicitAnexo) return elementos

    val chunks = elementos.chunks
    val lastIdx = chunks.lastIndexWhere { case (m, _) => m == LocalData || m == Assinatura }
    if (lastIdx < 0) return elementos
    if (lastIdx != chunks.length - 1) return elementos

    val (m, bs) = chunks(lastIdx)
    val splitAt = if (profile != null) signingRegionEnd(bs, profile) else signingRegionEndDefault(bs)
    val (kept, tail0) = bs.splitAt(splitAt)
    val tail = trimEmptyPars(tail0)
    if (tail.isEmpty || !looksLikeAnexoContent(tail)) return elementos

    val newChunks = chunks.take(lastIdx) ++ List((m, kept), (Anexo, tail))
    elementos.withChunks(newChunks)
  }

  private val defaultLocalDataRe = List(
    "^sala da sess"r, "^sala das sess"r, "^camara dos deputados"r,
    "^senado federal"r, "^brasilia,"r, "^rio de janeiro,"r,
    "^congresso nacional,"r
  )

  private def signingRegionEndDefault(blocks: List[Block]): Int = {
    val isSig = (p: Paragraph) => {
      val n = normalizer.normalize(p.text.trim.toLowerCase)
      defaultLocalDataRe.exists(_.findFirstIn(n).isDefined)
    }
    var i = 0
    var lastSigIdx = -1
    while (i < blocks.length) {
      blocks(i) match {
        case p: Paragraph if p.text.trim.isEmpty =>
        case p: Paragraph if isSig(p) || looksLikePersonName(p) =>
          lastSigIdx = i
        case _: Paragraph => return lastSigIdx + 1
        case _: Table | _: OL => return lastSigIdx + 1
        case _ => return lastSigIdx + 1
      }
      i += 1
    }
    lastSigIdx + 1
  }

  private val anexoHeadRe = "(?i)^anexo\\b".r

  private def oneOf(r: List[Regex]) = (b: Block) => b match {
    case p: Paragraph => r.find(_.findFirstIn(p.text).isDefined).map(_ => p)
    case _ => None
  }

  private def matchesOneOf(r: List[Regex]) = oneOf(r).andThen(_.isDefined)

  private def doesNotMatchAnyOf(r: List[Regex]) = oneOf(r).andThen(_.isEmpty)

  def reconheceLinks(b: Block, urnContexto: String): Block = b.mapBlock {
    case d: Dispositivo => d.conteudo match {
      case Some(p: Paragraph) =>
        import br.gov.lexml.parser.pl.linker.Linker.findLinks
        val (links, nl) = findLinks(urnContexto, p.nodes)
        d copy(links = links, conteudo = Some(p copy (nodes = nl)))
      case _ => d
    }
    case p: Paragraph =>
      import br.gov.lexml.parser.pl.linker.Linker.findLinks
      val (_, nl) = findLinks(urnContexto, p.nodes)
      p copy (nodes = nl)
    case x => x
  }

  def reconheceLinks(bl: List[Block], urnContexto: String): List[Block] = bl.map(reconheceLinks(_, urnContexto))

  @unused
  def printArticulacao(bl: List[Block], num: Int) = {
    println("articulacao" + num + ":")

    def printBlock(b: Block, indent: String = ""): Unit = b match {
      case p: Paragraph => println(indent + "P: text = " + p.text + ", fechaAspas = " + p.fechaAspas + ", na = " + p.notaAlteracao + "\n" +
        indent + "   nodes = " + p.toNodeSeq)
      case o: Omissis => println(indent + "O: fechaAspas = " + o.fechaAspas + ", na =  " + o.notaAlteracao)
      case a: Alteracao =>
        println(indent + "A: id = " + a.id + ", base = " + a.baseURN)
        a.blocks.foreach(printBlock(_, indent + "  "))
      case d: Dispositivo =>
        println(indent + "D: id = " + d.id + ", rotulo = " + d.rotulo + ", fechaAspas = " + d.fechaAspas + ", na = " + d.notaAlteracao)
        d.conteudo.foreach(b => printBlock(b, indent + "  conteudo: "))
        d.children.foreach(printBlock(_, indent + "    "))
      case _: Table => println(indent + "<TABLE>")
      case _: OL => println(indent + "<OL>")
      case _ => println(indent + "<SOMETHING>")
    }

    bl.foreach(printBlock(_))
  }

  def limpaParagrafosVazios(blocks: List[Block]): List[Block] = blocks.filter {
    case p: Paragraph if p.text == "" => false
    case _ => true
  }

  private def trimParagraphs(b: Block) = b match {
    case p: Paragraph =>
      val t = p.nodes.text
      val start = t.takeWhile(_.isWhitespace).length
      val end = t.reverse.takeWhile(_.isWhitespace).length
      val p1 = if (end > 0) {
        p.cutRight(end)
      } else {
        p
      }
      val p2 = if (start > 0) {
        p.cutLeft(start)
      } else {
        p
      }
      p2
    case x => x
  }
}

object ProjetoLei {
  def firstThat(l: List[Block], f: Block => Option[Block]): Option[Block] = {
    def h(l: List[Block]): Option[Block] = {
      l match {
        case Nil => None
        case x :: r => f(x) match {
          case None => h(r)
          case y => y
        }
      }
    }
    h(l)
  }

  def existsAnyThat[R](l: List[Block], f: Block => Boolean): Boolean =
    firstThat(l, _.searchFirst(f)).isDefined

  def existsAnyThatP[R](l: List[Block], f: PartialFunction[Block, Boolean]): Boolean =
    firstThat(l, _.searchFirst(f.lift(_).getOrElse(false))).isDefined

  /**
   * Attempts to parse the ID (number and date) from the original epigrafe text.
   * This is used when metadata doesn't provide an ID.
   */
  def parseEpigrafeId(epigrafeBlock: Block): Option[metadado.Id] = {
    import metadado.{Id, Data}

    epigrafeBlock match {
      case p: Paragraph =>
        val text = normalizer.normalize(p.text)

        // Debug: print what text we're trying to parse
        System.err.println(s"[DEBUG parseEpigrafeId] Original text: '${p.text}'")
        System.err.println(s"[DEBUG parseEpigrafeId] Normalized text: '$text'")

        // Pattern to extract number and date
        // Matches patterns like: "Nº 1.234, de 8 de dezembro de 2025" or "nº 123, de 2025"
        val numeroDataPattern = """.*[Nn][oº°˚]\s*([0-9.,]+).*?de\s+(.+)""".r

        text match {
          case numeroDataPattern(numeroStr, dataStr) =>
            System.err.println(s"[DEBUG parseEpigrafeId] Regex matched! Number: '$numeroStr', Date: '$dataStr'")
            try {
              // Remove formatting from number (dots and commas)
              val num = numeroStr.replaceAll("[.,\\s]", "").toInt
              System.err.println(s"[DEBUG parseEpigrafeId] Parsed number: $num")

              // Map of month names (Portuguese)
              val mesesMap = Map(
                "janeiro" -> 1, "fevereiro" -> 2, "marco" -> 3, "março" -> 3,
                "abril" -> 4, "maio" -> 5, "junho" -> 6, "julho" -> 7,
                "agosto" -> 8, "setembro" -> 9, "outubro" -> 10,
                "novembro" -> 11, "dezembro" -> 12
              )

              // Try to parse extensive date format: "8 de dezembro de 2025"
              val dataExtensaPattern = """(\d+)\s+de\s+(\w+)\s+de\s+(\d{4})""".r
              // Try to parse just year: "2025"
              val anoPattern = """^\s*(\d{4})\s*$""".r

              val anoOuData: Option[Either[Int, Data]] = dataStr.trim.toLowerCase match {
                case dataExtensaPattern(dia, mes, ano) =>
                  System.err.println(s"[DEBUG parseEpigrafeId] Matched extensive date: dia=$dia, mes=$mes, ano=$ano")
                  mesesMap.get(mes).map(m => Right(Data(ano.toInt, m, dia.toInt)))
                case anoPattern(ano) =>
                  System.err.println(s"[DEBUG parseEpigrafeId] Matched year only: ano=$ano")
                  Some(Left(ano.toInt))
                case _ =>
                  System.err.println(s"[DEBUG parseEpigrafeId] Date pattern did not match: '$dataStr'")
                  None
              }

              anoOuData.map { ad =>
                System.err.println(s"[DEBUG parseEpigrafeId] Successfully parsed ID: num=$num, date=$ad")
                Id(num = num, anoOuData = ad)
              }
            } catch {
              case ex: NumberFormatException =>
                System.err.println(s"[DEBUG parseEpigrafeId] NumberFormatException: ${ex.getMessage}")
                None
              case ex: Exception =>
                System.err.println(s"[DEBUG parseEpigrafeId] Exception: ${ex.getMessage}")
                None
            }
          case _ =>
            System.err.println(s"[DEBUG parseEpigrafeId] Regex did NOT match!")
            None
        }
      case _ => None
    }
  }
}