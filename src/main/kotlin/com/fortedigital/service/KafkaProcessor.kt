package com.fortedigital.service

import com.fortedigital.dto.TeamDTO
import com.fortedigital.repository.*
import com.fortedigital.service.formats.*
import io.ktor.util.logging.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class KafkaProcessor(
    bootstrapServers: String,
    private val questionRepository: QuestionRepository,
    private val answerRepository: AnswerRepository,
    private val teamRepository: TeamRepository
) {
    private val logger = KtorSimpleLogger("KafkaProcessor")
    private val consumerProps =
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.StringDeserializer",
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            ConsumerConfig.GROUP_ID_CONFIG to "admin-"+ Uuid.randomUuid(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false",
        )

    private val consumer = KafkaConsumer<String, ByteArray>(consumerProps)
    private val closed = AtomicBoolean(false) // Add this line

    private val jsonMapper = Json {
        ignoreUnknownKeys = true
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            closed.set(true)
            logger.info("Shutting down Kafka consumer")
            consumer.wakeup()
        })
    }

    suspend fun run() {
        logger.info("Starting Kafka consumer")
        try {

            consumer.subscribe(listOf("kafka-game"))
            while (true) {
                val records = consumer.poll(Duration.ofMillis(100))
                for (record in records) {
                    handle(String(record.value()))
                }
                consumer.commitSync()
            }
        } catch (e: WakeupException) {
            // Ignore exception if closing
            if (!closed.get()) throw e
        } finally {
            consumer.close()
        }
    }

    private suspend fun handle(message: String) {
        logger.info("Handling message: $message")
        try {
            val commonObject = jsonMapper.decodeFromString<Common>(message)
            if (commonObject.type == Type.QUESTION) {
                handleQuestion(message)
                return
            }
            logger.info("Handling message of category: ${commonObject.category}")
            when(commonObject.category) {
                Category.TEAM_REGISTRATION -> {
                    handleTeamRegistrationMessage(message)
                }
                Category.PING_PONG -> {
                    handlePingPong(message)
                }
                Category.ARITHMETIC -> {
                    handleArithmetic(message)
                }
                Category.BASE_64 -> {
                    handleBase64(message)
                }
                Category.PRIME_NUMBER -> {
                    handlePrimeNumber(message)
                }
                Category.TRANSACTIONS -> {
                    handleTransactions(message)
                }
                Category.MIN_MAX -> {
                    handleMinMax(message)
                }
                Category.DEDUPLICATION -> {
                    handleDeduplication(message)
                }
                else -> {
                    logger.error("Unknown category: ${commonObject.category}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling message: $message", e)
        }
    }

    private suspend fun handleQuestion(message: String) {
        val question = jsonMapper.decodeFromString<QuestionMessage>(message)
        val byQuestionId = questionRepository.getByQuestionId(question.messageId)
        if (byQuestionId != null) {
            logger.error("Received question exists in db, ignoring")
        } else {
            // check if created ends with Z and append it if not
            val created = Instant.parse(if (question.created.endsWith("Z")) question.created else "${question.created}Z")
            val newQuestion = Question(0, question.messageId, question.question, question.category, created)
            val id = questionRepository.create(newQuestion)
            logger.info("Question created with id: $id")
        }
    }

    private suspend fun handleTeamRegistrationMessage(message: String) {
        val teamRegistration = handleCommon<AnswerMessage>(message) ?: return
        logger.info("Team registration: ${teamRegistration.teamName} - ${teamRegistration.answer}")
        // determine if answer is a hex-color
        val hexColorRegex = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")
        if (!hexColorRegex.matches(teamRegistration.answer)) {
            logError(teamRegistration.teamName, "Answer is not a hex-color")
            return
        }
        // check if team already exists
        if (teamRepository.checkIfTeamExists(teamRegistration.teamName)) {
            logError(teamRegistration.teamName, "Team already exists")
            return
        }
        // create team
        val team = TeamDTO(0, teamRegistration.teamName, 0, teamRegistration.answer, emptyList())
        val id = teamRepository.create(team)
        logger.info("Team created with id: $id and name: ${teamRegistration.teamName}")
        val answer = Answer(
            0,
            id,
            Category.TEAM_REGISTRATION.score,
            teamRegistration.messageId,
            teamRegistration.questionId,
            teamRegistration.category,
            teamRegistration.created,
        )
        val create = answerRepository.create(answer)
        logger.info("Answer created with id: $create for team: ${teamRegistration.teamName}")
    }

    private suspend fun handlePingPong(message: String) {
        val pingPong = handleCommon<AnswerMessage>(message) ?: return
        if (pingPong.answer != "pong") {
            logError(pingPong.teamName, "Answer is not pong")
            return
        }



        val team = teamRepository.getTeamByName(pingPong.teamName)

        val answer = Answer(
            0,
            team.id,
            Category.PING_PONG.score,
            pingPong.messageId,
            pingPong.questionId,
            pingPong.category,
            pingPong.created,
        )
        val create = answerRepository.create(answer)
        logger.info("Answer created with id: $create for team: ${pingPong.teamName}")
    }

    private suspend fun handleArithmetic(message: String) {
        val arithmetic = handleCommon<AnswerMessage>(message) ?: return
        val answerValue = arithmetic.answer.toIntOrNull() ?: run {
            logError(arithmetic.teamName, "Answer is not a number")
            return
        }

        // check if answer is correct
        val question = questionRepository.getByQuestionId(arithmetic.questionId)
        val mathFunction = question!!.question.split("(")[0].toString()
        val expectedValue = when(true) {
            (mathFunction.split("+").size == 2) -> mathFunction.split("+")[0].trim().toInt() + mathFunction.split("+")[1].trim().toInt()
            (mathFunction.split("-").size == 2) -> mathFunction.split("-")[0].trim().toInt() - mathFunction.split("-")[1].trim().toInt()
            (mathFunction.split("*").size == 2) -> mathFunction.split("*")[0].trim().toInt() * mathFunction.split("*")[1].trim().toInt()
            (mathFunction.split("/").size == 2) -> mathFunction.split("/")[0].trim().toInt() / mathFunction.split("/")[1].trim().toInt()
            else -> {
                logError(arithmetic.teamName, "Unknown math function")
                return
            }
        }

        if (expectedValue != answerValue) {
            logError(arithmetic.teamName, "Answer is not correct")
            return
        }

        val team = teamRepository.getTeamByName(arithmetic.teamName)

        val answerEntity = Answer(
            0,
            team.id,
            Category.ARITHMETIC.score,
            arithmetic.messageId,
            arithmetic.questionId,
            arithmetic.category,
            arithmetic.created,
        )

        val create = answerRepository.create(answerEntity)
        logger.info("Answer created with id: $create for team: ${arithmetic.teamName}")
    }


    private suspend fun handleBase64(message: String) {
        val base64 = handleCommon<AnswerMessage>(message) ?: return
        // base64 decode answer
        val decodedAnswer = Base64.getDecoder().decode(base64.answer)

        // check if decoded value matches with value in question
        val question = questionRepository.getByQuestionId(base64.questionId)

        val expectedValue = question!!.question.split("echo ")[1]
        if (expectedValue != String(decodedAnswer)) {
            logError(base64.teamName, "Answer is not correct")
            return
        }


        val team = teamRepository.getTeamByName(base64.teamName)

        val answer = Answer(
            0,
            team.id,
            Category.BASE_64.score,
            base64.messageId,
            base64.questionId,
            base64.category,
            base64.created,
        )
        val create = answerRepository.create(answer)
        logger.info("Answer created with id: $create for team: ${base64.teamName}")
    }

    private suspend fun handlePrimeNumber(message: String) {
        val prime = handleCommon<AnswerMessage>(message) ?: return
        val isPrime = when(prime.answer) {
            "true" -> true
            "false" -> false
            else -> {
                logError(prime.teamName, "Answer is not true or false")
                return
            }
        }

        // check if value in question is prime
        val question = questionRepository.getByQuestionId(prime.questionId)
        val value = question!!.question.split("?")[1].trim().toInt()
        if (isPrime != isPrime(value)) {
            logError(prime.teamName, "Answer is not correct")
            return
        }

        val team = teamRepository.getTeamByName(prime.teamName)

        val answer = Answer(
            0,
            team.id,
            Category.PRIME_NUMBER.score,
            prime.messageId,
            prime.questionId,
            prime.category,
            prime.created,
        )
        val create = answerRepository.create(answer)
        logger.info("Answer created with id: $create for team: ${prime.teamName}")
    }

    private fun isPrime(value: Int): Any? {
        if (value < 2) {
            return false
        }
        for (i in 2..value / 2) {
            if (value % i == 0) {
                return false
            }
        }
        return true
    }

    private suspend fun handleTransactions(message: String) {
        val transactions = handleCommon<AnswerMessage>(message) ?: return
        val currentBalance = transactions.answer.toIntOrNull()
        if (currentBalance == null) {
            logError(transactions.teamName, "Answer is not a number")
            return
        }

        // calculate balance based on previous questions
        val question = questionRepository.getByQuestionId(transactions.questionId)
        val previousQuestions = questionRepository.getPreviousQuestions(question!!).plus(question)
        val expectedBalance = previousQuestions
            .map {
                val split = it.question.split(" ")
                when(split[0]) {
                    "INNSKUDD" -> split[1].toInt()
                    "UTTREKK" -> -split[1].toInt()
                    else -> 0
                }
            }.sum()


        if (currentBalance != expectedBalance) {
            logError(transactions.teamName, "Answer is not correct")
            return
        }

        val team = teamRepository.getTeamByName(transactions.teamName)
        val answer = Answer(
            0,
            team.id,
            Category.TRANSACTIONS.score,
            transactions.messageId,
            transactions.questionId,
            transactions.category,
            transactions.created,
        )
        val create = answerRepository.create(answer)
        logger.info("Answer created with id: $create for team: ${transactions.teamName}")
    }

    private suspend fun handleMinMax(message: String) {
        val minMax = handleCommon<AnswerMessage>(message) ?: return
        val answerValue = minMax.answer.toIntOrNull()
        if (answerValue == null) {
            logError(minMax.teamName, "Answer is not a number")
            return
        }

        // check if answer is correct
        val question = questionRepository.getByQuestionId(minMax.questionId)
        val split = question!!.question.split(" i ")
        val values = split[1].trim().removeSurrounding("[", "]").split(",").map { it.trim().toInt() }
        val expectedValue = when(split[0].trim()) {
            "HOYESTE" -> values.max()
            "LAVESTE" -> values.min()
            else -> {
                logError(minMax.teamName, "Unknown HOYESTE/LAVESTE value")
                return
            }
        }

        if (answerValue != expectedValue) {
            logError(minMax.teamName, "Answer is not correct")
            return
        }

        val team = teamRepository.getTeamByName(minMax.teamName)

        val answer = Answer(
            0,
            team.id,
            Category.MIN_MAX.score,
            minMax.messageId,
            minMax.questionId,
            minMax.category,
            minMax.created,
        )

        val create = answerRepository.create(answer)
        logger.info("Answer created with id: $create for team: ${minMax.teamName}")
    }

    private suspend fun handleDeduplication(message: String) {
        val deduplication = handleCommon<AnswerMessage>(message) ?: return
        val answerValue = deduplication.answer

        when(answerValue) {
            "you wont dupe me!" -> {
                val team = teamRepository.getTeamByName(deduplication.teamName)

                val answer = Answer(
                    0,
                    team.id,
                    Category.DEDUPLICATION.score,
                    deduplication.messageId,
                    deduplication.questionId,
                    deduplication.category,
                    deduplication.created,
                )
                val create = answerRepository.create(answer)
                logger.info("Answer created with id: $create for team: ${deduplication.teamName}")

            }
            "you duped me!" -> {
                answerRepository.deleteByQuestionId(deduplication.questionId)
            }
            else -> {
                logError(deduplication.teamName, "Answer is not correct")
                return
            }
        }
        if (!answerValue.contentEquals("you wont dupe me!")) {
            logError(deduplication.teamName, "Answer is not correct")
            logger.error("Answer is not correct")
            return
        }
    }


    private suspend inline fun<reified T: CommonMessage> handleCommon(message: String): T? {
        val answer = jsonMapper.decodeFromString<T>(message)
        logger.info("Handling message from team: ${answer.teamName} for category: ${answer.category}")

        val questionsExistsForAnswer = questionsExistsForAnswer(answer.questionId)
        if (!questionsExistsForAnswer) {
            logError(answer.teamName, "Question does not exist for this answer")
            return null
        }

        val byMessageId = answerRepository.getByMessageId(answer.messageId)
        if (byMessageId != null) {
            logError(answer.teamName, "Answer already exists")
            return null
        }

        if (answer !is AnswerMessage) {
            if (!teamRepository.checkIfTeamExists(answer.teamName)) {
                logError(answer.teamName, "Team does not exists")
                return null
            }
        }

        return answer
    }

    private suspend fun questionsExistsForAnswer(questionsId: String): Boolean {
        val byQuestionId = questionRepository.getByQuestionId(questionsId)

        return byQuestionId != null
    }

    private fun logError(teamName: String, message: String) {
        logger.error(message, "teamName", teamName)
    }

}
