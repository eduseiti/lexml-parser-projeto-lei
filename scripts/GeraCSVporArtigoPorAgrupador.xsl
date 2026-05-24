<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:math="http://www.w3.org/2005/xpath-functions/math"
    exclude-result-prefixes="xs math" xmlns:lx="http://www.lexml.gov.br/1.0"  xmlns="http://www.lexml.gov.br/1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xpath-default-namespace="http://www.lexml.gov.br/1.0"
    version="3.0">
    
    
    <xsl:variable name="uri" select="document-uri(/)"/>
    <xsl:variable name="filename"
        select="replace($uri, '^.*/', '')"/>
    
    <xsl:output method="text" omit-xml-declaration="yes"/>
    <xsl:param name="sigla"><xsl:value-of select="substring-before($filename, '.xml')"/></xsl:param>
    <xsl:param name="escopo"><xsl:value-of select="//Ementa"/></xsl:param>
    
    <!-- Function to escape commas and quotes -->
    <xsl:template name="escape">
        <xsl:param name="text"/>
        <xsl:choose>
            <xsl:when test="contains($text, ',') or contains($text, '&quot;')">
                <!-- Enclose in quotes and replace inner quotes with double quotes -->
                <xsl:text>&quot;</xsl:text>
                <xsl:call-template name="replace-quotes">
                    <xsl:with-param name="text" select="$text"/>
                </xsl:call-template>
                <xsl:text>&quot;</xsl:text>
            </xsl:when>
            <xsl:otherwise>
                <!-- No need to escape... mas mesmo assim, forcarei  -->
                <xsl:text>&quot;</xsl:text><xsl:value-of select="$text"/><xsl:text>&quot;</xsl:text>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>
    
    <!-- Function to replace quotes with double quotes -->
    <xsl:template name="replace-quotes">
        <xsl:param name="text"/>
        <xsl:choose>
            <xsl:when test="contains($text, '&quot;')">
                <xsl:value-of select="substring-before($text, '&quot;')"/>
                <xsl:text>&quot;&quot;</xsl:text>
                <xsl:call-template name="replace-quotes">
                    <xsl:with-param name="text" select="substring-after($text, '&quot;')"/>
                </xsl:call-template>
            </xsl:when>
            <xsl:otherwise>
                <xsl:value-of select="$text"/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>
    
    <xsl:template name="calculaPos">
        <xsl:variable name="qtPrec" select="count(preceding::Artigo[not(ancestor::Alteracao)]|preceding::Caput[not(ancestor::Alteracao)]|preceding::Paragrafo[not(ancestor::Alteracao)]|preceding::Parte[not(ancestor::Alteracao)]|preceding::Livro[not(ancestor::Alteracao)]|preceding::Titulo[not(ancestor::Alteracao)]|preceding::Subtitulo[not(ancestor::Alteracao)]|preceding::Capitulo[not(ancestor::Alteracao)]|preceding::Secao[not(ancestor::Alteracao)]|preceding::Subsecao[not(ancestor::Alteracao)]|preceding::Inciso[not(ancestor::Alteracao)]|preceding::Alinea[not(ancestor::Alteracao)]|preceding::Item[not(ancestor::Alteracao)]) "/>
        <xsl:variable name="qtAnc" select="count(ancestor::Artigo[not(ancestor::Alteracao)]|ancestor::Caput[not(ancestor::Alteracao)]|ancestor::Paragrafo[not(ancestor::Alteracao)]|ancestor::Parte[not(ancestor::Alteracao)]|ancestor::Livro[not(ancestor::Alteracao)]|ancestor::Titulo[not(ancestor::Alteracao)]|ancestor::Subtitulo[not(ancestor::Alteracao)]|ancestor::Capitulo[not(ancestor::Alteracao)]|ancestor::Secao[not(ancestor::Alteracao)]|ancestor::Subsecao[not(ancestor::Alteracao)]|ancestor::Inciso[not(ancestor::Alteracao)]|ancestor::Alinea[not(ancestor::Alteracao)]|ancestor::Item[not(ancestor::Alteracao)]) "/>
        <xsl:variable name="qtF" select="count(descendant::Artigo[not(ancestor::Alteracao)]|descendant::Caput[not(ancestor::Alteracao)]|descendant::Paragrafo[not(ancestor::Alteracao)]|descendant::Parte[not(ancestor::Alteracao)]|descendant::Livro[not(ancestor::Alteracao)]|descendant::Titulo[not(ancestor::Alteracao)]|descendant::Subtitulo[not(ancestor::Alteracao)]|descendant::Capitulo[not(ancestor::Alteracao)]|descendant::Secao[not(ancestor::Alteracao)]|descendant::Subsecao[not(ancestor::Alteracao)]|descendant::Inciso[not(ancestor::Alteracao)]|descendant::Alinea[not(ancestor::Alteracao)]|descendant::Item[not(ancestor::Alteracao)])"/>
        <xsl:value-of select="($qtPrec * 2) + $qtAnc +1"/><xsl:text>,</xsl:text><xsl:value-of select="($qtPrec * 2) + $qtAnc + 1 + ($qtF * 2) + 1"/><xsl:text>,</xsl:text>
    </xsl:template>
    
    <xsl:template match="/">
        <xsl:text>Tipo,Rotulo,Num_Inicio,Num_Final,Texto,urn</xsl:text><xsl:text>&#10;</xsl:text>
        <xsl:for-each select="//Artigo/Caput[not(ancestor::Alteracao)]">
            <xsl:text>"CPT","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:value-of select="../Rotulo"/><xsl:text>, caput",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
          
            
            <xsl:for-each select="../Paragrafo[not(ancestor::Alteracao)]">
                <xsl:text>"PAR","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:value-of select="../Rotulo"/><xsl:text>, </xsl:text> <xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
                <xsl:call-template name="calculaPos"/>
                <xsl:variable name="conteudo"><xsl:value-of select="descendant::p"/></xsl:variable>
                <xsl:variable name="NconteudoPAR" select="normalize-space($conteudo)"/>
                <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
                <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoPAR)"/></xsl:call-template><xsl:text></xsl:text>
                <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
                <xsl:text>&#10;</xsl:text>
            </xsl:for-each>
        </xsl:for-each>
        <xsl:for-each select="//Artigo[not(ancestor::Alteracao)]">
            <xsl:text>"ART","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Parte[not(ancestor::Alteracao)]">
            <xsl:text>"PRT","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::NomeAgrupador|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor-or-self::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Livro[not(ancestor::Alteracao)]">
            <xsl:text>"LIV","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Parte"><xsl:value-of select="ancestor::Parte/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::NomeAgrupador|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor-or-self::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Titulo[not(ancestor::Alteracao)]">
            <xsl:text>"TIT","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Parte"><xsl:value-of select="ancestor::Parte/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Livro"><xsl:value-of select="ancestor::Livro/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::NomeAgrupador|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor-or-self::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Subtitulo[not(ancestor::Alteracao)]">
            <xsl:text>"SBT","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Parte"><xsl:value-of select="ancestor::Parte/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Livro"><xsl:value-of select="ancestor::Livro/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Titulo"><xsl:value-of select="ancestor::Titulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::NomeAgrupador|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor-or-self::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Capitulo[not(ancestor::Alteracao)]">
            <xsl:text>"CAP","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Parte"><xsl:value-of select="ancestor::Parte/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Livro"><xsl:value-of select="ancestor::Livro/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Titulo"><xsl:value-of select="ancestor::Titulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Subitulo"><xsl:value-of select="ancestor::Subtitulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::NomeAgrupador|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor-or-self::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Secao[not(ancestor::Alteracao)]">
            <xsl:text>"SEC","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Parte"><xsl:value-of select="ancestor::Parte/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Livro"><xsl:value-of select="ancestor::Livro/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Titulo"><xsl:value-of select="ancestor::Titulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Subitulo"><xsl:value-of select="ancestor::Subtitulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Capitulo"><xsl:value-of select="ancestor::Capitulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::NomeAgrupador|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor-or-self::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Subsecao[not(ancestor::Alteracao)]">
            <xsl:text>"SSC","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Parte"><xsl:value-of select="ancestor::Parte/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Livro"><xsl:value-of select="ancestor::Livro/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Titulo"><xsl:value-of select="ancestor::Titulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Subitulo"><xsl:value-of select="ancestor::Subtitulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Capitulo"><xsl:value-of select="ancestor::Capitulo/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:if test="ancestor::Secao"><xsl:value-of select="ancestor::Secao/Rotulo"/><xsl:text>, </xsl:text></xsl:if><xsl:value-of select="Rotulo"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::NomeAgrupador|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:for-each select="ancestor-or-self::*/NomeAgrupador"><xsl:value-of select="concat(., ' | ')"/></xsl:for-each></xsl:variable>
            <xsl:text></xsl:text><xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template><xsl:text></xsl:text>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Inciso[not(ancestor::Alteracao)]">
            <xsl:text>"INC","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:value-of select="ancestor::Artigo/Rotulo"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Paragrafo/Rotulo"><xsl:value-of select="ancestor::Paragrafo/Rotulo"/></xsl:if><xsl:if test="not(ancestor::Paragrafo/Rotulo)"><xsl:text>caput</xsl:text></xsl:if> <xsl:text>, Inciso </xsl:text><xsl:value-of select=" substring-before(Rotulo, ' –')"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:choose>
                <xsl:when test="ancestor::Paragrafo"><xsl:value-of select="ancestor::Artigo/Rotulo|ancestor::Paragrafo/p|ancestor::Paragrafo/Rotulo"/></xsl:when>
                <xsl:otherwise><xsl:value-of select="ancestor::Artigo/Rotulo|ancestor::Caput/p" separator=" "/></xsl:otherwise>
            </xsl:choose></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Alinea[not(ancestor::Alteracao)]">
            <xsl:text>"ALI","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:value-of select="ancestor::Artigo/Rotulo"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Paragrafo/Rotulo"><xsl:value-of select="ancestor::Paragrafo/Rotulo"/></xsl:if><xsl:if test="not(ancestor::Paragrafo/Rotulo)"><xsl:text>caput</xsl:text></xsl:if> <xsl:if test="ancestor::Inciso/Rotulo"><xsl:text>, Inciso </xsl:text><xsl:value-of select="substring-before(ancestor::Inciso/Rotulo, ' –')"/></xsl:if><xsl:text>, Alínea </xsl:text><xsl:value-of select="substring-before(Rotulo, ')')"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:choose>
                <xsl:when test="ancestor::Paragrafo"><xsl:value-of select="ancestor::Artigo/Rotulo|ancestor::Paragrafo/p/text()|ancestor::Paragrafo/Rotulo|ancestor::Inciso/p/text()|ancestor::Inciso/Rotulo|ancestor::Item/Rotulo"/></xsl:when>
                <xsl:otherwise><xsl:value-of select="ancestor::Artigo/Rotulo|ancestor::Caput/p|ancestor::Inciso/Rotulo|ancestor::Inciso/p|ancestor::Item/p/text()"/></xsl:otherwise>
            </xsl:choose></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="//Item[not(ancestor::Alteracao)]">
            <xsl:text>"ITE","</xsl:text><xsl:value-of select="$sigla"/><xsl:text>, </xsl:text><xsl:value-of select="ancestor::Artigo/Rotulo"/><xsl:text>, </xsl:text><xsl:if test="ancestor::Paragrafo/Rotulo"><xsl:value-of select="ancestor::Paragrafo/Rotulo"/></xsl:if><xsl:if test="not(ancestor::Paragrafo/Rotulo)"><xsl:text>caput, </xsl:text></xsl:if> <xsl:if test="ancestor::Inciso/Rotulo"><xsl:text>, Inciso </xsl:text><xsl:value-of select="substring-before(ancestor::Inciso/Rotulo, ' –')"/></xsl:if><xsl:if test="ancestor::Alinea/Rotulo"><xsl:text>, Alínea </xsl:text><xsl:value-of select="substring-before(ancestor::Alinea/Rotulo, ')')"/></xsl:if><xsl:text>, Item </xsl:text><xsl:value-of select=" substring-before(Rotulo, '.')"/><xsl:text>",</xsl:text>
            <xsl:call-template name="calculaPos"/>
            <xsl:variable name="conteudo"><xsl:value-of select="descendant::p//text()|descendant::Rotulo"/></xsl:variable>
            <xsl:variable name="conteudoContexto"><xsl:if test="string-length($escopo) &gt; 0"><xsl:value-of select="$escopo"/><xsl:text> | </xsl:text></xsl:if><xsl:choose>
                <xsl:when test="ancestor::Paragrafo"><xsl:value-of select="ancestor::Artigo/Rotulo|ancestor::Paragrafo/p|ancestor::Paragrafo/Rotulo|ancestor::Inciso/p|ancestor::Inciso/Rotulo|ancestor::Alinea/p|ancestor::Alinea/Rotulo"/></xsl:when>
                <xsl:otherwise><xsl:value-of select="ancestor::Artigo/Rotulo|ancestor::Caput/p|ancestor::Inciso/Rotulo|ancestor::Inciso/p/text()|ancestor::Alinea/p|ancestor::Alinea/Rotulo"/></xsl:otherwise>
            </xsl:choose></xsl:variable>
            <xsl:variable name="NconteudoART" select="normalize-space($conteudo)"/>
            <xsl:call-template name="escape"><xsl:with-param name="text" select="concat('[ ', normalize-space($conteudoContexto), ' ] ',  $NconteudoART)"/></xsl:call-template>
            <xsl:value-of select="concat(',',/LexML/Metadado[1]/Identificacao[1]/@URN, '!',   @id)"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
    </xsl:template>
</xsl:stylesheet>