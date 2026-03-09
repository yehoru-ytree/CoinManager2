package MailAggregator.MailAggregator.monobank.repository.jpa

import com.fasterxml.jackson.databind.JsonNode
import com.vladmihalcea.hibernate.type.json.JsonType
import jakarta.persistence.*
import org.hibernate.annotations.Type
import java.util.UUID

@Entity
@Table(name = "transaction_status", schema = "bankaggregator")
data class TransactionStatusJpaEntity(
    @Id
    @Column(name = "transaction_id", nullable = false)
    val transactionId: String,

    @Column(name = "status", nullable = false)
    val status: String,
)
