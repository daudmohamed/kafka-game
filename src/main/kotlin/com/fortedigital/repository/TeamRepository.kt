package com.fortedigital.repository

import com.fortedigital.dto.AnswerDTO
import com.fortedigital.dto.CategoryScoreDTO
import com.fortedigital.dto.TeamDTO
import org.jetbrains.exposed.dao.id.IntIdTable
import com.fortedigital.repository.DatabaseFactory.Companion.dbQuery
import com.fortedigital.service.formats.Category
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction


class Team(
    val id: Int,
    private val name: String,
    private val hexColor: String,
    val answers: List<AnswerDTO>,
)  {
    constructor(id: Int, name: String, hexColor: String) : this(id, name, hexColor, emptyList())

    fun toDTO(): TeamDTO {
        val distinctAnswers = answers.distinctBy { it.questionId }
        var categoryAnswers: List<CategoryScoreDTO> = distinctAnswers.groupBy { it.category }
            .filter { it.key != Category.DEDUPLICATION}
            .map { (category, a) ->
               CategoryScoreDTO(category.ordinal, category, a.sumOf { it.score }, a.size, false)
            }


        var deduplicationAnswers = answers.filter { it.category == Category.DEDUPLICATION }
            .groupBy { it.questionId }
        if (deduplicationAnswers.isNotEmpty()) {
            var hasError = false
            var deduplicationScore = 0
            var deduplicationCount = 0
            deduplicationAnswers
                .forEach { (_, a) ->
                    if (a.size > 1) {
                        hasError = true
                    } else {
                        deduplicationScore += a.sumOf { it.score }
                        deduplicationCount++
                    }
                }
            val deduplicationCategory: CategoryScoreDTO?
            if (hasError) {
                deduplicationCategory = CategoryScoreDTO(Category.DEDUPLICATION.ordinal, Category.DEDUPLICATION, 0, 0, hasError)
            } else {
                deduplicationCategory = CategoryScoreDTO(Category.DEDUPLICATION.ordinal, Category.DEDUPLICATION, deduplicationScore, deduplicationCount, hasError)
            }
            categoryAnswers = categoryAnswers.plus(deduplicationCategory)
        }


        /*Category.entries.forEach {
            if (categoryAnswers.none { categoryScoreDTO -> categoryScoreDTO.category == it }) {
                categoryAnswers = categoryAnswers.plus(CategoryScoreDTO(it.ordinal, it, 0, 0, false))
            }
        }*/

        val score = 0 + categoryAnswers.sumOf { it.totalScore }

        val highestAnswerId = answers.maxByOrNull { it.id }?.id ?: 0
        return TeamDTO(id, name, score,hexColor, categoryAnswers, highestAnswerId)
    }
}
class TeamRepository {
    object TeamTable : IntIdTable() {
        val name = varchar("name", 256)
        val hexColor = varchar("hex_color", 7).default("#FF0000")

        fun toModel(it: ResultRow) = Team(
            it[id].value,
            it[name],
            it[hexColor]
        )
        fun toModel(it: ResultRow, toList: List<AnswerDTO>) = Team(
            it[id].value,
            it[name],
            it[hexColor],
            toList
        )
    }

    suspend fun create(team: TeamDTO): Int = dbQuery {
        TeamTable.insertAndGetId {
            it[name] = team.name
            it[hexColor] = team.hexColor
        }.value
    }

    fun list(): List<Team> {
        return transaction {
            TeamTable.selectAll()
                .map{row ->
                    TeamTable.toModel(
                        row,
                        AnswerRepository.AnswerTable.select(where = AnswerRepository.AnswerTable.teamId eq row[TeamTable.id])
                            .map(AnswerRepository.AnswerTable::toModel)
                            .map { it.toDTO()}
                            .toList()
                    )
                }
        }
    }

    suspend fun getTeamByName(teamName: String): Team {
        return dbQuery {
            TeamTable.select { TeamTable.name eq teamName }
                .map(TeamTable::toModel)
                .single()
        }
    }

    fun checkIfTeamExists(teamName: String): Boolean {
        return transaction {
            TeamTable.select { TeamTable.name eq teamName }
                .singleOrNull() != null
        }
    }
}
