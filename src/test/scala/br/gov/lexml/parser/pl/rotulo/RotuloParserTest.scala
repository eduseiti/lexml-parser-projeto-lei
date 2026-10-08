package br.gov.lexml.parser.pl.rotulo

import br.gov.lexml.parser.pl.text.normalizer
import org.junit.Assert._
import org.junit.Test

/** `rotuloParser.parseRotulo` on the normalized text of a paragraph. */
class RotuloParserTest {

  private def rotulo(s: String): Option[Rotulo] = rotuloParser.parseRotulo(normalizer.normalize(s)).map(_._1)

  private def eq(expected: Any, actual: Any): Unit =
    assertEquals(expected.asInstanceOf[AnyRef], actual.asInstanceOf[AnyRef])

  @Test def livroRomano(): Unit = {
    eq(Some(RotuloLivro(Right(2), None)), rotulo("LIVRO II"))
    eq(Some(RotuloLivro(Right(3), None)), rotulo("LIVRO III - DA TRIBUTAÇÃO NA FONTE"))
  }

  @Test def livroTextual(): Unit = {
    eq(Some(RotuloLivro(Left("PRIMEIRO"), None)), rotulo("LIVRO PRIMEIRO"))
    eq(Some(RotuloLivro(Left("COMPLEMENTAR"), None)), rotulo("LIVRO COMPLEMENTAR"))
    eq(Some(RotuloLivro(Left("IIITRIBUTACAO"), None)), rotulo("LIVRO IIITRIBUTAÇÃO"))
  }

  /** RIR/2018 (Decreto 9.580) sub-headings under "Dos livros comerciais". */
  @Test def livroComercialNaoERotulo(): Unit = {
    for (s <- Seq("Livro diário", "Livro de Apuração do Lucro Real", "Livro razão",
                  "Livro de Presença", "Livro auxiliar n. 8", "Livro de inventário"))
      eq(None, rotulo(s))
  }

  /** Lei 6.766, art. 4: "III-A. - texto" (dot between complement and dash). */
  @Test def incisoComPontoAposComplemento(): Unit = {
    eq(Some(RotuloInciso(3, Some(0))), rotulo("III-A. - ao longo da faixa de domínio das ferrovias"))
    eq(Some(RotuloInciso(3, Some(1))), rotulo("III-B – ao longo das águas correntes"))
    eq(Some(RotuloInciso(3, None)), rotulo("III - ao longo das faixas"))
  }

  /** RIR/2018, art. 677: "V I - para os meses..." is inciso VI with a stray space. */
  @Test def incisoComEspacoNoNumeral(): Unit = {
    eq(Some(RotuloInciso(6, None)), rotulo("V I - para os meses de abril a dezembro do ano-calendário de 2015:"))
    eq(Some(RotuloInciso(5, None)), rotulo("V - para o ano-calendário de 2014"))
    eq(None, rotulo("V I para os meses"))
  }
}
