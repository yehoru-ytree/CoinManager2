package MailAggregator.MailAggregator.common.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

/**
 * One row per (household, year, month, category) — the frozen row offset that category occupies
 * in that specific monthly sheet tab. See V10__MonthCategoryLayout.sql for the design.
 */
@Entity
@Table(name = "month_category_layout", schema = "bankaggregator")
data class MonthCategoryLayoutJpaEntity(
    @EmbeddedId
    val id: MonthCategoryLayoutId,

    @Column(name = "row_offset", nullable = false)
    val rowOffset: Int,
)

@Embeddable
data class MonthCategoryLayoutId(
    @Column(name = "household_id", nullable = false)
    val householdId: UUID,

    @Column(name = "year", nullable = false)
    val year: Int,

    @Column(name = "month", nullable = false)
    val month: Int,

    @Column(name = "category_id", nullable = false)
    val categoryId: UUID,
) : Serializable
