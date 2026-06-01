package com.example.embabelclj.kotlin

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("Insights estruturados extraidos de uma fotografia de natureza.")
data class NatureInsights(

    @get:JsonPropertyDescription("Resumo em 2-3 frases, em portugues do Brasil, do que a foto mostra.")
    val resumo: String,

    @get:JsonPropertyDescription("Bioma identificado. Ex.: Floresta Boreal (Taiga), Cerrado, Mata Atlantica, Deserto.")
    val bioma: String,

    @get:JsonPropertyDescription("Tipo de vegetacao predominante (ex.: floresta de coniferas, estepe xerofila).")
    val tipoVegetacao: String,

    @get:JsonPropertyDescription("Arvores/plantas provaveis, por nome comum e/ou cientifico.")
    val arvoresProvaveis: List<String>,

    @get:JsonPropertyDescription("Regiao/pais provavel, com um raciocinio curto que sustente o palpite.")
    val localProvavel: String,

    @get:JsonPropertyDescription("Clima provavel (ex.: arido quente BWh, boreal/subartico).")
    val clima: String,

    @get:JsonPropertyDescription("Estacao do ano estimada, com a pista que a sugere.")
    val estacaoEstimada: String,

    @get:JsonPropertyDescription("Pistas visuais que sustentam a analise (vegetacao, terreno, luz, marcos).")
    val pistasVisuais: List<String>,

    @get:JsonPropertyDescription("Fauna possivel para o bioma/regiao inferidos.")
    val faunaPossivel: List<String>,

    @get:JsonPropertyDescription("Confianca geral da analise, numero de 0.0 a 1.0.")
    val confianca: Double,
)
