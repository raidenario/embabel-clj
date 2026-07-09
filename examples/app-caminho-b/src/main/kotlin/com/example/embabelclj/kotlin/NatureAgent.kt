package com.example.embabelclj.kotlin

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.AgentImage
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.createObject

@Agent(
    description = "Analisa uma foto de natureza e gera insights estruturados " +
        "(bioma, vegetacao, arvores provaveis, local provavel, clima, fauna)."
)
class NatureAgent {

    @AchievesGoal(
        description = "Gerar insights estruturados sobre uma fotografia de natureza"
    )
    @Action
    fun analyzeNature(image: AgentImage, context: OperationContext): NatureInsights =
        context.ai()
            .withLlm(VISION_MODEL)
            .withImage(image)
            .createObject<NatureInsights>(ANALYSIS_PROMPT)

    companion object {
        const val VISION_MODEL = "gemini-2.5-flash"

        const val ANALYSIS_PROMPT =
            "Voce e um naturalista de campo, botanico e biogeografo experiente. " +
                "Analise com atencao a fotografia de natureza anexada e infira o maximo " +
                "possivel a partir da vegetacao, do terreno, da luz e de marcos visiveis. " +
                "Responda em portugues do Brasil. Seja especifico, mas honesto sobre a incerteza."
    }
}
