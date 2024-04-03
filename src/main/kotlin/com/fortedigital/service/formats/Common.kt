package com.fortedigital.service.formats

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
open class Common(val type: Type, val category: Category)

interface CommonMessage {
    val messageId: String
    val questionId: String
    val category: Category
    val teamName: String
}

@Serializable
class QuestionMessage (
    val created: String,
    val question: String,
    val messageId: String,
    val category: Category
)

@Serializable
class AnswerMessage (
    val created: String,
    val answer: String,
    override val messageId: String,
    override val teamName: String,
    override val questionId: String,
    override val category: Category,
) : CommonMessage


@Serializable
enum class Type {
    QUESTION, ANSWER
}

@Serializable
enum class Category(val score: Int) {
    @SerialName("team-registration")
    TEAM_REGISTRATION(1), // 1 TASK
    @SerialName("ping-pong")
    PING_PONG(5), // 5 TASKS
    @SerialName("arithmetic")
    ARITHMETIC(10), // 10 TASKS
    @SerialName("base64")
    BASE_64(15), // 10 TASKS
    @SerialName("is-a-prime")
    PRIME_NUMBER(20), // 10 TASKS
    @SerialName("transactions")
    TRANSACTIONS(30), // 20 TASKS
    @SerialName("min-max")
    MIN_MAX(45), // 5 TASKS
    @SerialName("deduplication")
    DEDUPLICATION(60), // 3 TASKS
}
