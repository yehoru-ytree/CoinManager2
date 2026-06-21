package MailAggregator.MailAggregator.common.repository.jpa

import com.fasterxml.jackson.databind.JsonNode
import com.vladmihalcea.hibernate.type.json.JsonType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Type
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "category", schema = "bankaggregator")
data class CategoryJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "name", nullable = false)
    val name: String,

    @Column(name = "display_name", nullable = false)
    val displayName: String,

    @Column(name = "sheet_row", nullable = false)
    val sheetRow: Int,

    @Column(name = "priority", nullable = false)
    val priority: Int,

    @Type(JsonType::class)
    @Column(name = "keywords", columnDefinition = "jsonb", nullable = false)
    val keywords: JsonNode,

    @Column(name = "is_other", nullable = false)
    val isOther: Boolean,

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,
)
