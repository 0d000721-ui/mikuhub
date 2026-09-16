package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentQuestionsTest {
    private fun parse(questions: String) = parseAgentQuestions("""{"questions":$questions}""").getOrThrow()
    private fun single() = parse("""[{"id":"theme","question":"选择主题","options":["初音绿","跟随系统"],"selection_type":"single"}]""").single()

    @Test fun legacyStringOptionsRemainCompatible() {
        val question = single()
        assertEquals(listOf("初音绿", "跟随系统"), question.options.map { it.label })
        assertEquals("", question.header)
        assertEquals("初音绿", question.answer(AgentQuestionDraft(selected = listOf("初音绿"))))
    }

    @Test fun descriptionsHeaderAndRecommendationArePreserved() {
        val question = parse("""[{"id":"a","header":"主题","question":"颜色？","options":["绿色","蓝色"],"option_descriptions":["初音配色","冷色"],"recommended_option":"绿色"}]""").single()
        assertEquals("主题", question.header)
        assertEquals("初音配色", question.options[0].description)
        assertEquals("绿色", question.recommendedOption)
        assertEquals("", question.answer(AgentQuestionDraft()))
    }

    @Test fun objectOptionsFromExistingCodexStyleRequestsAlsoRender() {
        val question = parse("""[{"id":"a","question":"颜色？","options":[{"label":"绿色","description":"初音配色"}]}]""").single()
        assertEquals(AgentQuestionOption("绿色", "初音配色"), question.options.single())
    }

    @Test fun unknownRecommendationDoesNotCreateAnOption() {
        assertNull(parse("""[{"id":"a","question":"颜色？","recommended_option":"绿色"}]""").single().recommendedOption)
    }

    @Test fun selectingCustomAnswerReplacesSingleChoiceAndRetainsDraft() {
        val draft = AgentQuestionDraft(selected = listOf("初音绿"), text = "  粉色  ", custom = true)
        assertEquals("粉色", single().answer(draft))
        assertEquals("初音绿", single().answer(draft.copy(custom = false)))
    }

    @Test fun emptyCustomAnswerCannotSubmitPreviouslySelectedSingleChoice() {
        assertNull(buildAgentAnswers(listOf(single()), mapOf("theme" to AgentQuestionDraft(listOf("初音绿"), "  ", true))))
    }

    @Test fun multiAnswersUseDisplayedOrderAndCanIncludeFreeText() {
        val question = single().copy(multiple = true)
        assertEquals("初音绿, 跟随系统, 蓝色", question.answer(AgentQuestionDraft(listOf("跟随系统", "初音绿"), "蓝色", true)))
        assertEquals("初音绿", question.answer(AgentQuestionDraft(listOf("初音绿"), "隐藏的草稿", false)))
    }

    @Test fun staleOptionsAreNeverReturnedAsAnswers() {
        assertEquals("", single().answer(AgentQuestionDraft(selected = listOf("已删除选项"))))
    }

    @Test fun textOnlyQuestionWorksWithoutSelectionType() {
        val question = parse("""[{"id":"a","question":"你的要求？"}]""").single()
        assertEquals("更流畅", question.answer(AgentQuestionDraft(text = " 更流畅 ")))
    }

    @Test fun allQuestionsMustBeAnsweredBeforeSubmitting() {
        val questions = listOf(single(), single().copy(id = "second"))
        val drafts = mapOf("theme" to AgentQuestionDraft(selected = listOf("初音绿")))
        assertNull(buildAgentAnswers(questions, drafts))
        val payload = buildAgentAnswers(questions, drafts + ("second" to AgentQuestionDraft(text = "自由输入", custom = true)))!!
        assertEquals(mapOf("theme" to "初音绿", "second" to "自由输入"), readAgentAnswers(payload))
    }

    @Test fun emptyRequestNeverProducesAnEmptySuccessfulAnswer() {
        assertTrue(parseAgentQuestions("""{"questions":[]}""").isFailure)
        assertNull(buildAgentAnswers(emptyList(), emptyMap()))
    }

    @Test fun malformedInputAndDuplicateIdsAreRejectedWithoutDroppingQuestions() {
        listOf("oops", "[]", "{}", """{"questions":[{"id":"a","question":"ok"},false]}""",
            """{"questions":[{"id":"a","question":"one"},{"id":"a","question":"two"}]}""",
            """{"questions":[{"id":"a","question":""}]}""",
            """{"questions":[{"question":"missing ID"}]}"""
        ).forEach { assertTrue(it, parseAgentQuestions(it).isFailure) }
    }

    @Test fun malformedOptionOrDuplicateLabelsCannotBeSubmitted() {
        listOf("[false]", "[\"same\",\"same\"]", "[{\"description\":\"no label\"}]").forEach {
            assertTrue(parseAgentQuestions("""{"questions":[{"id":"a","question":"q","options":$it}]}""").isFailure)
        }
    }

    @Test fun existingRequestsWithMoreThanThreeQuestionsAreNotTruncated() {
        val input = (1..4).joinToString(",") { """{"id":"$it","question":"question $it"}""" }
        assertEquals(4, parse("[$input]").size)
    }

    @Test fun draftsSurviveSerializationIncludingHiddenCustomText() {
        val drafts = mapOf("theme" to AgentQuestionDraft(listOf("初音绿"), "保留输入", false))
        assertEquals(drafts, Json.decodeFromString<Map<String, AgentQuestionDraft>>(Json.encodeToString(drafts)))
    }

    @Test fun invalidStoredAnswerDoesNotCrashHistory() {
        listOf("[]", "null", "oops", """{"answers":false}""").forEach { assertTrue(readAgentAnswers(it).isEmpty()) }
    }
}
