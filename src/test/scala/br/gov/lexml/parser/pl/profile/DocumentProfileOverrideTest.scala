package br.gov.lexml.parser.pl.profile

import br.gov.lexml.parser.pl.metadado.{Data, Id, Metadado}
import org.junit.Assert._
import org.junit.Test

/** `DocumentProfileOverride` keeps the base profile's epígrafe template unless the head or tail
 * is overridden (work_history/20260925_130918_numero_complemento_and_constituicao_epigrafe_fix.md). */
class DocumentProfileOverrideTest {

  private def epigrafe(profile: DocumentProfile, id: Id): String =
    Metadado(profile, id = Some(id), hashFonte = None).epigrafePadrao

  private val cf88 = Id(num = 1988, anoOuData = Right(Data(1988, 10, 5)))

  @Test def constituicaoSemOverrideUsaTemplateDoPerfil(): Unit =
    assertEquals("Constituição da República Federativa do Brasil",
      epigrafe(ConstituicaoFederal + OverridesData(), cf88))

  @Test def constituicaoComOverrideDeLocalidadeUsaTemplateDoPerfil(): Unit =
    assertEquals("Constituição da República Federativa do Brasil",
      epigrafe(ConstituicaoFederal + OverridesData(overrideUrnFragLocalidade = Some(Some("br"))), cf88))

  @Test def headSobrescritoUsaTemplateGenerico(): Unit =
    assertEquals("CONSTITUIÇÃO Nº 1.988, DE 5 DE OUTUBRO DE 1988",
      epigrafe(ConstituicaoFederal + OverridesData(overrideEpigrafeHead = Some("CONSTITUIÇÃO")), cf88).trim)

  @Test def leiComHeadSobrescritoNaoMuda(): Unit =
    assertEquals("INSTRUÇÃO NORMATIVA Nº 1.500, DE 29 DE OUTUBRO DE 2014",
      epigrafe(Lei + OverridesData(overrideEpigrafeHead = Some("INSTRUÇÃO NORMATIVA")),
        Id(num = 1500, anoOuData = Right(Data(2014, 10, 29)))).trim)

  @Test def medidaProvisoriaComComplemento(): Unit =
    assertEquals("MEDIDA PROVISÓRIA Nº 2.228-1, DE 6 DE SETEMBRO DE 2001",
      epigrafe(MedidaProvisoriaFederal + OverridesData(),
        Id(num = 2228, complemento = Some(1), anoOuData = Right(Data(2001, 9, 6)))).trim)
}
