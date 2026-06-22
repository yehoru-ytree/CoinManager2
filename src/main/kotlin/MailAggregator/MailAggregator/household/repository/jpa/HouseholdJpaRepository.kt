package MailAggregator.MailAggregator.household.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HouseholdJpaRepository : JpaRepository<HouseholdJpaEntity, UUID>
