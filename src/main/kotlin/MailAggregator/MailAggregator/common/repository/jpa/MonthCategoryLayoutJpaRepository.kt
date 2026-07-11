package MailAggregator.MailAggregator.common.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface MonthCategoryLayoutJpaRepository :
    JpaRepository<MonthCategoryLayoutJpaEntity, MonthCategoryLayoutId> {

    fun findAllByIdHouseholdIdAndIdYearAndIdMonth(
        householdId: UUID,
        year: Int,
        month: Int,
    ): List<MonthCategoryLayoutJpaEntity>

    @Modifying
    @Query(
        "DELETE FROM MonthCategoryLayoutJpaEntity e " +
            "WHERE e.id.householdId = :householdId AND e.id.year = :year AND e.id.month = :month",
    )
    fun deleteAllByHouseholdMonth(
        @Param("householdId") householdId: UUID,
        @Param("year") year: Int,
        @Param("month") month: Int,
    ): Int
}
