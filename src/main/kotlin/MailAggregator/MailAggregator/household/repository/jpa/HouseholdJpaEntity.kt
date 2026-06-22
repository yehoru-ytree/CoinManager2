package MailAggregator.MailAggregator.household.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "household", schema = "bankaggregator")
data class HouseholdJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "name")
    val name: String? = null,

    @Column(name = "sheet_id", nullable = false)
    val sheetId: String,

    @Column(name = "template_sheet_title", nullable = false)
    val templateSheetTitle: String,
)
