package com.fortedigital

import com.fortedigital.config.configureRouting
import com.fortedigital.config.configureSerialization
import com.fortedigital.repository.AnswerRepository
import com.fortedigital.repository.DatabaseFactory
import com.fortedigital.repository.QuestionRepository
import com.fortedigital.repository.TeamRepository
import com.fortedigital.service.KafkaProcessor
import io.ktor.server.application.*
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    //configureMonitoring()
    configureSerialization()
    DatabaseFactory(
        dbHost = environment.config.property("database.host").getString(),
        dbPort = environment.config.property("database.port").getString(),
        dbUser = environment.config.property("database.user").getString(),
        dbPassword = environment.config.property("database.password").getString(),
        databaseName = environment.config.property("database.databaseName").getString(),
        embedded = environment.config.property("database.embedded").getString().toBoolean(),
    ).init()
    val teamRepository = TeamRepository()
    val questionRepository = QuestionRepository()
    val answerRepository = AnswerRepository()
    configureRouting(teamRepository)

    val bootstrapServers = environment.config.property("kafka.bootstrap.servers").getString()
    launch {

        KafkaProcessor(bootstrapServers, questionRepository,answerRepository, teamRepository).apply { run() }
    }
}
