package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one to three concise questions when you need clarification or additional information.
        Each question can optionally provide a list of suggested options for the user to choose from.
        Prefer two or three short, mutually exclusive options for single selection. Use option_descriptions to explain tradeoffs.
        Put the recommended option first and set recommended_option to its exact label. Do not add an Other option; the UI provides it.
        Use a short header to label each question. Do not use this tool to bypass execution approval.
        The user may provide a free-text answer for every question, including single and multi selection questions.
        For multi selection questions, custom text can be combined with selected options.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "One to three questions to ask the user")
                    put("minItems", 1)
                    put("maxItems", 3)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("header", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional short topic label, e.g. Theme or Install")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of suggested options for the user to choose from"
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select one option or enter custom text), multi (select options and/or enter custom text)"
                                )
                            })
                            put("option_descriptions", buildJsonObject {
                                put("type", "array")
                                put("description", "Optional brief explanations, in the same order as options")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("recommended_option", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional exact label of the recommended option. Choosing or recommending an option never submits it automatically.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
