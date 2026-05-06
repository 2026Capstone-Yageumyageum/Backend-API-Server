package com.capstone.backend.domain.analysis.repository

import com.capstone.backend.domain.analysis.entity.ReferenceModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReferenceModelRepository : JpaRepository<ReferenceModel, Long> {
    fun findById(id: String): List<ReferenceModel>
}
