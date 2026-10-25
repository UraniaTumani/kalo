package com.kalo.driver.controller;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.driver.repository.DriverInvitationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartnerDriverInvitationDisabledIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationRepository invitations;
    @Test void issuanceRouteIsAbsentByDefault() throws Exception {
        var company=fixtures.approvedCompany();
        mockMvc.perform(post("/api/v1/partner/drivers/1/invitations")
                .header("Authorization",bearer(tokenFor(company.getOwner())))).andExpect(status().isNotFound());
        assertThat(invitations.count()).isZero();
    }
}
