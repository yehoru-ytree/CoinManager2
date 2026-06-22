package MailAggregator.MailAggregator.household.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface InviteTokenJpaRepository : JpaRepository<InviteTokenJpaEntity, String>
