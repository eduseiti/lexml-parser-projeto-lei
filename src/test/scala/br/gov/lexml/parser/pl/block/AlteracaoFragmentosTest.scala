package br.gov.lexml.parser.pl.block

import br.gov.lexml.parser.pl.errors.ParseException
import org.junit.Assert._
import org.junit.Test

import scala.xml.Text

/** `Block.reconheceAlteracoes` (fecha/abre-aspas recognition) and
 * `Block.juntaFragmentos` (paragraph-normalization pass). */
class AlteracaoFragmentosTest {

  private def p(s: String): Paragraph = Paragraph(List(Text(s)))

  private def eq(expected: Any, actual: Any): Unit =
    assertEquals(expected.asInstanceOf[AnyRef], actual.asInstanceOf[AnyRef])

  private def alteracoes(bl: List[Block]): List[Alteracao] = bl.collect { case a: Alteracao => a }

  private def textos(bl: List[Block]): List[String] = bl.collect { case q: Paragraph => q.unormalizedText }

  private def nota(a: Alteracao): Option[String] = a.blocks.last match {
    case q: Paragraph => q.notaAlteracao
    case _ => None
  }

  /** Caput + Alteração lines + a following article; returns the Alteração. */
  private def umaAlteracao(linhas: String*): Alteracao = {
    val res = Block.reconheceAlteracoes(
      p("Art. 1º A Lei nº 1, de 2000, passa a vigorar com a seguinte redação:") ::
        linhas.map(p).toList ::: List(p("Art. 2º Esta Lei entra em vigor na data de sua publicação.")))
    val alts = alteracoes(res)
    eq(1, alts.size)
    eq("Art. 2º Esta Lei entra em vigor na data de sua publicação.", textos(res).last)
    alts.head
  }

  // ---- Fix B: punctuation / notes after the fecha-aspas ----

  @Test def fechaAspasComPonto(): Unit =
    eq(2, umaAlteracao("\"Art. 5º Texto novo.", "§ 1º Mais texto\".").blocks.size)

  @Test def fechaAspasComPontoEVirgula(): Unit =
    eq(2, umaAlteracao("\"Art. 5º Texto novo:", "I - item\";").blocks.size)

  @Test def fechaAspasPontoNR(): Unit = {
    val a = umaAlteracao("\"Art. 5º Texto novo.", "§ 1º Mais texto\". (NR)")
    eq(2, a.blocks.size)
    eq(Some("nr"), nota(a))
  }

  @Test def fechaAspasNRPonto(): Unit = {
    val a = umaAlteracao("\"Art. 5º Texto novo.", "§ 1º Mais texto\" (NR).")
    eq(2, a.blocks.size)
    eq(Some("nr"), nota(a))
  }

  @Test def termoEntreAspasNaoFecha(): Unit =
    eq(3, umaAlteracao("\"Art. 5º Considera-se:", "I - o termo \"x\".", "II - outro termo.\"").blocks.size)

  @Test def artigoUnicoEntreAspasNR(): Unit = {
    val a = umaAlteracao("“Art. 2o Texto novo.” (NR)")
    eq(1, a.blocks.size)
    eq(Some("nr"), nota(a))
  }

  @Test def fechaAspasIsoladoNR(): Unit = {
    val a = umaAlteracao("“Art. 5º Texto novo.", "§ 1º Mais texto.", "” (NR)")
    eq(3, a.blocks.size)
    eq(Some("nr"), nota(a))
  }

  @Test def ultimaLinhaComAbreAspas(): Unit =
    eq(3, umaAlteracao("“Art. 5º Texto novo.", "“§ 1º Mais texto.", "“§ 2º Último texto.” (NR)").blocks.size)

  @Test def semFechaAspasAindaFalha(): Unit =
    try {
      Block.reconheceAlteracoes(List(p("\"Art. 58-A Texto novo."), p("§ 1º Mais texto.")))
      fail("expected ParseException")
    } catch {
      case _: ParseException =>
    }

  // ---- Fix C: which paragraphs open an Alteração ----

  @Test def termoEntreAspasNoInicioNaoAbre(): Unit = {
    val res = Block.reconheceAlteracoes(List(p("ARTIGO 29"), p("\"Land\" Berlim"), p("O presente Acordo aplica-se.")))
    eq(0, alteracoes(res).size)
  }

  @Test def paragrafoUnicoEntreAspasAbre(): Unit =
    eq(1, umaAlteracao("\"§ 1º Texto novo\".").blocks.size)

  @Test def incisoComNotasAbre(): Unit = {
    val a = umaAlteracao("\"II - texto novo;\" (NR) (Revogado pela Lei nº 2, de 2001)")
    eq(1, a.blocks.size)
    eq(Some("nr"), nota(a))
  }

  // ---- Fix A: fragments ----

  @Test def juntaFragmentosAspas(): Unit = {
    val res = Block.juntaFragmentos(List(
      p("Art. 1º Em obediência ao disposto na alínea \" c"),
      p("\" do inciso III, do artigo 46 do Estatuto da Terra"),
      p(", e de sua regulamentação no Decreto 55.891."),
      p("Art. 2º Outro.")))
    eq(List("Art. 1º Em obediência ao disposto na alínea \" c\" do inciso III, do artigo 46 do Estatuto da Terra" +
      ", e de sua regulamentação no Decreto 55.891.", "Art. 2º Outro."), textos(res))
    eq(0, alteracoes(Block.reconheceAlteracoes(res)).size)
  }

  @Test def juntaFragmentoVirgulaAposAspas(): Unit =
    eq(List("I - recebimento de \" royalties\", aluguéis e juros;"),
      textos(Block.juntaFragmentos(List(p("I - recebimento de \" royalties"), p("\", aluguéis e juros;")))))

  @Test def naoJuntaOmissis(): Unit =
    eq(2, Block.juntaFragmentos(List(p("I - o termo \"x"), p("\"……… "))).size)

  @Test def naoJuntaAlinea(): Unit =
    eq(2, Block.juntaFragmentos(List(p("Art. 1º O termo \"x"), p("\" a) texto da alínea"))).size)

  @Test def naoJuntaSemAspasAbertas(): Unit =
    eq(2, Block.juntaFragmentos(List(p("Art. 1º O termo \"x\" é definido"), p("\" do inciso III"))).size)

  // ---- E: editorial notes ----

  @Test def notaAposCaput(): Unit =
    eq(List("Art. 1º Texto. [Redação dada pelo(a) Instrução Normativa RFB nº 1558, de 31 de março de 2015]",
      "Art. 2º Outro."),
      textos(Block.juntaFragmentos(List(p("Art. 1º Texto."),
        p("[Redação dada pelo(a) Instrução Normativa RFB nº 1558, de 31 de março de 2015]"),
        p("Art. 2º Outro.")))))

  @Test def notasConsecutivas(): Unit =
    eq(List("IV - [Incluído(a) pelo(a) IN RFB nº 1] [Revogado(a) pelo(a) IN RFB nº 2]"),
      textos(Block.juntaFragmentos(List(p("IV -"), p("[Incluído(a) pelo(a) IN RFB nº 1]"),
        p("[Revogado(a) pelo(a) IN RFB nº 2]")))))

  @Test def notaAposFechaAspasNR(): Unit = {
    val bl = Block.juntaFragmentos(List(
      p("Art. 1º A Lei nº 1, de 2000, passa a vigorar com a seguinte redação:"),
      p("“Art. 5º Texto novo.” (NR)"),
      p("[Redação dada pelo(a) Instrução Normativa RFB nº 2265, de 9 de maio de 2025]"),
      p("Art. 2º Esta Lei entra em vigor.")))
    val res = Block.reconheceAlteracoes(bl)
    eq(1, alteracoes(res).size)
    eq(Some("nr"), nota(alteracoes(res).head))
    eq("Art. 2º Esta Lei entra em vigor.", textos(res).last)
  }

  @Test def notaAposTabelaDescartada(): Unit = {
    val res = Block.juntaFragmentos(List(Table(<table/>), p("[Vide Instrução Normativa RFB nº 1]"), p("Art. 2º Outro.")))
    eq(2, res.size)
    eq(List("Art. 2º Outro."), textos(res))
  }

  @Test def colchetesSemPalavraChaveNaoJunta(): Unit =
    eq(2, Block.juntaFragmentos(List(p("Art. 1º Texto."), p("[Tabela 1]"))).size)
}
