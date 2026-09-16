package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class AgentQuestionOption(val label: String, val description: String = "")

internal data class AgentQuestion(
    val id: String,
    val question: String,
    val header: String,
    val options: List<AgentQuestionOption>,
    val multiple: Boolean,
    val recommendedOption: String?,
)

@Serializable
internal data class AgentQuestionDraft(
    val selected: List<String> = emptyList(),
    val text: String = "",
    val custom: Boolean = false,
)

private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Reject malformed requests as a whole, so we never silently omit an AI question. */
internal fun parseAgentQuestions(input: String): Result<List<AgentQuestion>> = runCatching {
    val root = Json.parseToJsonElement(input) as? JsonObject ?: error("问题格式无效")
    val questions = root["questions"] as? JsonArray ?: error("缺少问题列表")
    require(questions.isNotEmpty()) { "问题列表为空" }
    val ids = mutableSetOf<String>()
    questions.map { element ->
        val question = element as? JsonObject ?: error("问题格式无效")
        val id = question["id"].stringValue()?.takeIf { it.isNotBlank() } ?: error("问题缺少编号")
        require(ids.add(id)) { "问题编号重复" }
        val text = question["question"].stringValue()?.takeIf { it.isNotBlank() } ?: error("问题内容为空")
        val descriptions = question["option_descriptions"] as? JsonArray
        val rawOptions = question["options"]
        require(rawOptions == null || rawOptions == JsonNull || rawOptions is JsonArray) { "选项列表格式无效" }
        val options = (rawOptions as? JsonArray).orEmpty().mapIndexed { index, option ->
            when (option) {
                is JsonObject -> AgentQuestionOption(
                    label = option["label"].stringValue()?.takeIf { it.isNotBlank() } ?: error("选项内容为空"),
                    description = option["description"].stringValue().orEmpty(),
                )
                else -> AgentQuestionOption(
                    label = option.stringValue()?.takeIf { it.isNotBlank() } ?: error("选项格式无效"),
                    description = descriptions?.getOrNull(index).stringValue().orEmpty(),
                )
            }
        }
        require(options.map { it.label }.distinct().size == options.size) { "选项内容重复" }
        AgentQuestion(
            id = id,
            question = text,
            header = question["header"].stringValue().orEmpty(),
            options = options,
            multiple = question["selection_type"].stringValue() == "multi",
            recommendedOption = question["recommended_option"].stringValue()?.takeIf { label -> options.any { it.label == label } },
        )
    }
}

internal fun AgentQuestion.answer(draft: AgentQuestionDraft): String {
    if (options.isEmpty() || (!multiple && draft.custom)) return draft.text.trim()
    // Keep the displayed option order, irrespective of the order in which they were tapped.
    val selected = options.filter { it.label in draft.selected }.map { it.label }
    if (!multiple) return selected.singleOrNull().orEmpty()
    return (selected + listOfNotNull(draft.text.trim().takeIf { draft.custom && it.isNotEmpty() })).joinToString(", ")
}

internal fun buildAgentAnswers(questions: List<AgentQuestion>, drafts: Map<String, AgentQuestionDraft>): String? {
    if (questions.isEmpty()) return null
    val answers = questions.associate { it.id to it.answer(drafts[it.id] ?: AgentQuestionDraft()) }
    if (answers.values.any { it.isBlank() }) return null
    return buildJsonObject {
        put("answers", buildJsonObject { answers.forEach { (id, answer) -> put(id, answer) } })
    }.toString()
}

internal fun readAgentAnswers(answer: String): Map<String, String> = runCatching {
    val root = Json.parseToJsonElement(answer) as? JsonObject
    (root?.get("answers") as? JsonObject).orEmpty().mapValues { it.value.stringValue().orEmpty() }
}.getOrDefault(emptyMap())
