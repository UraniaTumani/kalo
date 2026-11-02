package com.kalo.document;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.common.util.LicenseValidity;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.assertj.core.api.Assertions.assertThat;

class FleetDocumentBlockersIntegrationTest extends AbstractIntegrationTest {
    @Test void selfAndPartnerReceiveSafeReasonsForAllFourDocuments() throws Exception {
        var taxi = fixtures.bookableCompany();
        var driver = fixtures.user(UserRole.DRIVER, UserStatus.ACTIVE);
        jdbcTemplate.update("UPDATE drivers SET user_id=?,phone=?,availability_status='OFFLINE' WHERE id=?", driver.getId(), driver.getPhone(), taxi.driver().getId());
        jdbcTemplate.update("DELETE FROM documents WHERE owner_type='DRIVER' AND owner_id=?", taxi.driver().getId());
        jdbcTemplate.update("UPDATE documents SET verification_status='PENDING' WHERE owner_type='VEHICLE' AND owner_id=? AND document_type='VEHICLE_REGISTRATION'", taxi.vehicle().getId());
        jdbcTemplate.update("UPDATE documents SET expires_at=? WHERE owner_type='VEHICLE' AND owner_id=? AND document_type='INSURANCE'", LicenseValidity.today().minusDays(1), taxi.vehicle().getId());
        jdbcTemplate.update("UPDATE documents SET expires_at=NULL WHERE owner_type='VEHICLE' AND owner_id=? AND document_type='TECHNICAL_INSPECTION'", taxi.vehicle().getId());
        var self = mockMvc.perform(put("/api/v1/driver/me/availability").header("Authorization", bearer(tokenFor(driver)))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"ONLINE\"}"))
            .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("FLEET_DOCUMENTS_REQUIRED"))
            .andExpect(jsonPath("$.documentIssues.length()").value(4))
            .andExpect(jsonPath("$.documentIssues[0].reason").value("MISSING"))
            .andExpect(jsonPath("$.documentIssues[1].reason").value("PENDING"))
            .andExpect(jsonPath("$.documentIssues[2].reason").value("EXPIRED"))
            .andExpect(jsonPath("$.documentIssues[3].reason").value("EXPIRY_MISSING"))
            .andExpect(jsonPath("$.documentIssues[0].ownerId").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertThat(self).doesNotContain("fileUrl", "test-fixture:", "rejectionReason", "documentNumber");
        mockMvc.perform(patch("/api/v1/partner/drivers/" + taxi.driver().getId() + "/availability")
                .header("Authorization", bearer(tokenFor(taxi.company().getOwner())))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"ONLINE\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.documentIssues.length()").value(4));
    }

    @Test void foreignPartnerCannotReadReasonsAndOfflineDoesNotRequireDocuments() throws Exception {
        var taxi = fixtures.bookableCompany();
        jdbcTemplate.update("DELETE FROM documents WHERE owner_type='DRIVER' AND owner_id=?", taxi.driver().getId());
        var other = fixtures.approvedCompany();
        mockMvc.perform(patch("/api/v1/partner/drivers/" + taxi.driver().getId() + "/availability")
                .header("Authorization", bearer(tokenFor(other.getOwner())))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"ONLINE\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.documentIssues").doesNotExist());
        mockMvc.perform(patch("/api/v1/partner/drivers/" + taxi.driver().getId() + "/availability")
                .header("Authorization", bearer(tokenFor(taxi.company().getOwner())))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"OFFLINE\"}"))
            .andExpect(status().isOk());
    }

    @Test void diagnosticsDescribeNewestVersionOnly() throws Exception {
        var taxi = fixtures.bookableCompany();
        jdbcTemplate.update("""
            INSERT INTO documents (owner_type,owner_id,document_type,file_url,expires_at,verification_status,rejection_reason,created_at,updated_at)
            SELECT owner_type,owner_id,document_type,file_url,expires_at,'REJECTED','PRIVATE_REVIEW_NOTE',now(),now()
            FROM documents WHERE owner_type='DRIVER' AND owner_id=?
            """, taxi.driver().getId());
        String body = mockMvc.perform(patch("/api/v1/partner/drivers/" + taxi.driver().getId() + "/availability")
                .header("Authorization", bearer(tokenFor(taxi.company().getOwner())))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"ONLINE\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.documentIssues.length()").value(1))
            .andExpect(jsonPath("$.documentIssues[0].reason").value("REJECTED"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("PRIVATE_REVIEW_NOTE", "file_url");
    }
}
