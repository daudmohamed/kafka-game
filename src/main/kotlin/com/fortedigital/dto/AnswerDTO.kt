package com.fortedigital.dto

import com.fortedigital.service.formats.Category
import kotlinx.serialization.Serializable

@Serializable
data class AnswerDTO(val id: Int, val category: Category, val score: Int, val questionId: String)
