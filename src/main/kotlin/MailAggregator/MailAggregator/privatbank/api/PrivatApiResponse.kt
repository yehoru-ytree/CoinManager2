package MailAggregator.MailAggregator.privatbank.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * ACP /statements/transactions envelope. Paginates via [existNextPage] / [nextPageId] — pull the
 * next page by re-issuing the same request with `followId=<nextPageId>`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrivatApiResponse(
    val status: String? = null,
    val transactions: List<PrivatApiTransaction> = emptyList(),
    @JsonProperty("exist_next_page") val existNextPage: Boolean = false,
    @JsonProperty("next_page_id") val nextPageId: String? = null,
)
