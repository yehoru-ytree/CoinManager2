package MailAggregator.MailAggregator.monobank.repository.jpa

import com.fasterxml.jackson.databind.JsonNode
import com.vladmihalcea.hibernate.type.json.JsonType
import jakarta.persistence.*
import org.hibernate.annotations.Type
import java.util.UUID

@Entity
@Table(name = "transaction", schema = "bankaggregator")
data class TransactionJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long,

    @Type(JsonType::class)
    @Column(name = "raw", columnDefinition = "jsonb", nullable = false)
    val raw: JsonNode
)
