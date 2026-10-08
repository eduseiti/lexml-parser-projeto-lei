package br.gov.lexml.parser.pl.block

import br.gov.lexml.parser.pl.metadado.Metadado
import br.gov.lexml.parser.pl.output.LexmlRenderer
import br.gov.lexml.parser.pl.profile.ProjetoDeLeiDoSenadoNoSenado
import br.gov.lexml.parser.pl.{Anexo, ProjetoLei, ProjetoLeiParser}
import org.junit.Assert._
import org.junit.Test

import scala.xml.{Elem, Node, NodeSeq, Text}

/** Treaty-style articles: a bare "Artigo N" line followed by unlabelled text
 * (`Block.anexaTextoArtigoSemCaput`), and the annex renderers that used to drop
 * (`renderAnexoArticulado`) or reorder (`renderAnexoBlocks`) that text. */
class ArtigoSemCaputTest {

  private def p(s: String): Paragraph = Paragraph(List(Text(s)))

  private def eq(expected: Any, actual: Any): Unit =
    assertEquals(expected.asInstanceOf[AnyRef], actual.asInstanceOf[AnyRef])

  private val parser = new ProjetoLeiParser(ProjetoDeLeiDoSenadoNoSenado)

  private def articulacao(linhas: String*): Elem =
    LexmlRenderer.renderArticulacao(parser.parseArticulacao(linhas.map(p).toList, useLinker = false, urnContexto = ""))

  private def norm(s: String): String = s.replaceAll("\\s+", " ").trim

  private def byId(root: NodeSeq, id: String): Node =
    (root \\ "_").find(_ \@ "id" == id).getOrElse(throw new AssertionError(s"no element with id $id in $root"))

  /** Own text of an element: its direct <p> children. */
  private def texto(n: Node): String = norm((n \ "p").text)

  private def titulo(n: Node): String = norm((n \ "TituloDispositivo").text)

  private def topLevelP(root: Node): List[String] = (root \ "p").map(x => norm(x.text)).toList

  @Test def corpoEAlineasDepoisDoRotulo(): Unit = {
    val x = articulacao(
      "Artigo 34",
      "O agente diplomático gozará de isenção de todos os impostos, com as exceções seguintes:",
      "a) os impostos indiretos;",
      "b) os impostos sobre bens imóveis;",
      "Artigo 35",
      "O Estado acreditado deverá isentar os agentes diplomáticos.")
    eq("O agente diplomático gozará de isenção de todos os impostos, com as exceções seguintes:",
      texto(byId(x, "art34_cpt")))
    eq("a) os impostos indiretos;", norm(byId(x, "art34_cpt_ali1").text))
    eq("b) os impostos sobre bens imóveis;", norm(byId(x, "art34_cpt_ali2").text))
    eq("O Estado acreditado deverá isentar os agentes diplomáticos.", texto(byId(x, "art35_cpt")))
    eq("", titulo(byId(x, "art35")))
    eq(Nil, topLevelP(x))
  }

  @Test def tituloEItensNumerados(): Unit = {
    val x = articulacao(
      "Artigo 102",
      "Composição, funcionamento e reuniões do Conselho de Administração (Const. 17)",
      "1. O Conselho de Administração compõe-se de um Presidente e de quarenta e um membros.",
      "2. A Presidência caberá, de direito, ao país anfitrião do Congresso.")
    val art = byId(x, "art102")
    eq("Composição, funcionamento e reuniões do Conselho de Administração (Const. 17)", titulo(art))
    eq("", texto(byId(x, "art102_cpt")))
    eq("1. O Conselho de Administração compõe-se de um Presidente e de quarenta e um membros.",
      norm(byId(x, "art102_cpt_ite1").text))
    eq(Nil, topLevelP(x))
  }

  @Test def tituloECorpo(): Unit = {
    val x = articulacao(
      "Artigo 15",
      "Transparência e Prestação de contas",
      "O Banco assegurará que seus processos sejam transparentes.",
      "Artigo 16",
      "O presente Tratado terá duração ilimitada.")
    eq("Transparência e Prestação de contas", titulo(byId(x, "art15")))
    eq("O Banco assegurará que seus processos sejam transparentes.", texto(byId(x, "art15_cpt")))
    // A short sentence with a final period is caput text, not a title.
    eq("", titulo(byId(x, "art16")))
    eq("O presente Tratado terá duração ilimitada.", texto(byId(x, "art16_cpt")))
  }

  @Test def variosParagrafosNoFimDoCapitulo(): Unit = {
    val x = articulacao(
      "CAPÍTULO I",
      "Nome e objeto",
      "ARTIGO 1",
      "Pelo presente Tratado, as Partes Contratantes estabelecem uma zona de livre comércio.",
      "A expressão Zona significa o conjunto dos territórios das Partes Contratantes.",
      "CAPÍTULO II",
      "Programa de Liberação",
      "ARTIGO 2",
      "As Partes Contratantes eliminarão os gravames.")
    eq("Pelo presente Tratado, as Partes Contratantes estabelecem uma zona de livre comércio. " +
      "A expressão Zona significa o conjunto dos territórios das Partes Contratantes.",
      texto(byId(x, "art1_cpt")))
    assertTrue((byId(x, "cap1") \\ "Artigo").exists(_ \@ "id" == "art1"))
    eq(Nil, topLevelP(x))
    eq(Nil, (byId(x, "cap1") \ "p").toList)
  }

  @Test def tituloAcimaDoArtigoNaoMuda(): Unit = {
    val x = articulacao(
      "Do imposto",
      "Art. 1º Fica instituído o imposto.",
      "Art. 2º Esta Lei entra em vigor na data de sua publicação.")
    eq("Do imposto", titulo(byId(x, "art1")))
    eq("Fica instituído o imposto.", texto(byId(x, "art1_cpt")))
  }

  @Test def fechoDoTratadoFicaFora(): Unit = {
    // The pass itself, on the flat block list: the closing formula is not taken. (Later,
    // organizaDispositivos appends any paragraph after a dispositivo with text to it, because its
    // "starts in lowercase" test runs on the normalized text; that is existing behaviour.)
    val bl = Block.anexaTextoArtigoSemCaput(Block.reconheceDispositivos(List(
      p("Artigo 53"),
      p("A presente Convenção entrará em vigor no trigésimo dia."),
      p("EM FÉ DO QUE, os plenipotenciários abaixo assinados firmaram a presente Convenção."),
      p("Feito em Viena, aos dezoito dias do mês de abril de mil novecentos e sessenta e um."))))
    val textos = bl.map {
      case d: Dispositivo => d.conteudo.collect { case q: Paragraph => q.unormalizedText }.getOrElse("-")
      case q: Paragraph => "p:" + q.unormalizedText.take(9)
      case x => x.toString
    }
    eq(List("-", "A presente Convenção entrará em vigor no trigésimo dia.", "p:EM FÉ DO ", "p:Feito em "), textos)
  }

  @Test def cabecalhoEmMaiusculasFicaFora(): Unit = {
    val x = articulacao(
      "Artigo 9",
      "O Instituto terá sede em San José.",
      "DISPOSIÇÕES TRANSITÓRIAS",
      "Artigo 10",
      "Esta Convenção será ratificada.")
    eq("O Instituto terá sede em San José.", texto(byId(x, "art9_cpt")))
    // Not part of art. 9; identificaTitulos then makes it the title of art. 10 (existing behaviour).
    eq("DISPOSIÇÕES TRANSITÓRIAS", titulo(byId(x, "art10")))
  }

  // ---- annex renderers ----

  private val pl = ProjetoLei(Metadado(ProjetoDeLeiDoSenadoNoSenado, hashFonte = None), Nil, p("LEI"), None, Nil, Nil)

  private def anexo(blocks: List[Block]): Anexo = Anexo(num = 1, titulo = Some(p("ANEXO")), blocks = blocks)

  private def artigo(n: Int, texto: String): Dispositivo =
    Dispositivo(br.gov.lexml.parser.pl.rotulo.RotuloArtigo(n), None,
      List(Dispositivo(br.gov.lexml.parser.pl.rotulo.RotuloParagrafo(), Some(p(texto)), Nil, Nil)), Nil)

  @Test def anexoArticuladoNaoPerdeParagrafos(): Unit = {
    val blocks = Block.identificaPaths(List(
      p("O Governo e o Banco acordam o seguinte:"), artigo(1, "O Banco terá sede em Xangai."),
      p("Texto solto entre artigos"), artigo(2, "O Banco terá personalidade jurídica."),
      p("Feito em Fortaleza, em 15 de julho de 2014.")))
    val doc = LexmlRenderer.renderAnexoDoc(pl, anexo(blocks))
    assertTrue((doc \\ "DocumentoArticulado").nonEmpty)
    eq("O Governo e o Banco acordam o seguinte:", norm((doc \\ "Preambulo").text))
    val ordem = (doc \\ "Articulacao").head.child.collect { case e: Elem => e.label + ":" + norm(e.text).take(12) }.toList
    eq(List("Artigo:Art. 1º O Ba", "p:Texto solto ", "Artigo:Art. 2º O Ba", "p:Feito em For"), ordem)
  }

  @Test def anexoGenericoMantemAOrdem(): Unit = {
    // A table forces the DocumentoGenerico path; text and dispositivos must stay in source order.
    val tabela = Table(<table><tr><td>x</td></tr></table>)
    val blocks = Block.identificaPaths(List(p("Antes"), artigo(1, "Texto do art. 1."), p("Depois"), tabela))
    val pp = (LexmlRenderer.renderAnexoDoc(pl, anexo(blocks)) \\ "PartePrincipal").head
    val ordem = pp.child.collect { case e: Elem => e.label + ":" + norm(e.text).take(12) }.toList
    eq(List("p:ANEXO", "p:Antes", "Artigo:Art. 1º Text", "p:Depois", "table:x"), ordem)
    eq("anexo1_tab3", ((pp \ "table").head \@ "id"))
  }
}
