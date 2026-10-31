package com.kalo.admin;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.*;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.document.service.LocalDocumentStorage;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.common.util.LicenseValidity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FleetDocumentReviewIntegrationTest extends AbstractIntegrationTest {
    @Autowired DocumentRepository documents;
    @Autowired LocalDocumentStorage storage;
    private final List<String> stored = new ArrayList<>();
    private record Fixture(String base, Document document, Long company, Long owner) {}
    private Fixture fixture(DocumentOwnerType type) {
        var company = fixtures.approvedCompany();
        Long owner = type == DocumentOwnerType.DRIVER
                ? fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE).getId()
                : fixtures.vehicle(company, VehicleStatus.ACTIVE).getId();
        var ref = storage.store(new MockMultipartFile("file", "proof.pdf", "application/pdf",
                "%PDF-1.4\nreview fixture\n%%EOF".getBytes(StandardCharsets.UTF_8)));
        stored.add(ref);
        var d = new Document(); d.setOwnerType(type); d.setOwnerId(owner);
        d.setDocumentType(type == DocumentOwnerType.DRIVER ? DocumentType.DRIVING_LICENSE : DocumentType.INSURANCE);
        d.setFileUrl(ref); d.setIssuedAt(LicenseValidity.today().minusYears(1));
        d.setExpiresAt(LicenseValidity.today().plusYears(1)); d.setVerificationStatus(DocumentVerificationStatus.DRAFT);
        documents.saveAndFlush(d);
        return new Fixture("/api/v1/admin/partners/" + company.getId() + "/fleet/" + type + "/" + owner + "/documents", d, company.getId(), owner);
    }
    private String admin() throws Exception { return bearer(tokenFor(fixtures.user(UserRole.ADMIN, UserStatus.ACTIVE))); }
    @AfterEach void discardFiles() { stored.forEach(storage::discard); }

    @Test void adminCanListDownloadAndApproveBothOwnerTypes() throws Exception {
        String auth = admin();
        for (var type : new DocumentOwnerType[]{DocumentOwnerType.DRIVER, DocumentOwnerType.VEHICLE}) {
            var f = fixture(type);
            mockMvc.perform(get(f.base()).header("Authorization", auth)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$[0].id").value(f.document().getId()))
                    .andExpect(jsonPath("$[0].fileUrl").doesNotExist());
            mockMvc.perform(get(f.base() + "/" + f.document().getId() + "/file").header("Authorization", auth))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().bytes("%PDF-1.4\nreview fixture\n%%EOF".getBytes(StandardCharsets.UTF_8)));
            mockMvc.perform(post(f.base() + "/" + f.document().getId() + "/approve").header("Authorization", auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("APPROVED"));
            assertThat(documents.findById(f.document().getId()).orElseThrow().getVerificationStatus()).isEqualTo(DocumentVerificationStatus.APPROVED);
        }
    }
    @Test void rejectionRequiresReasonAndPersistsTrimmedReason() throws Exception {
        var f = fixture(DocumentOwnerType.VEHICLE); String auth = admin();
        var url = f.base() + "/" + f.document().getId() + "/reject";
        for (String reason : new String[]{"", "   ", "x".repeat(1001)}) {
            mockMvc.perform(post(url).header("Authorization", auth).contentType(APPLICATION_JSON)
                    .content("{\"reason\":\"" + reason + "\"}")).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post(url).header("Authorization", auth).contentType(APPLICATION_JSON)
                .content("{\"reason\":\"  Unreadable document  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rejectionReason").value("Unreadable document"));
        assertThat(documents.findById(f.document().getId()).orElseThrow().getVerificationStatus()).isEqualTo(DocumentVerificationStatus.REJECTED);
    }
    @Test void anonymousAndNonAdminsCannotReadOrDecide() throws Exception {
        var f = fixture(DocumentOwnerType.DRIVER);
        mockMvc.perform(get(f.base())).andExpect(status().isUnauthorized());
        for (var role : new UserRole[]{UserRole.CUSTOMER, UserRole.PARTNER, UserRole.DRIVER}) {
            var user = fixtures.user(role, UserStatus.ACTIVE);
            if (role == UserRole.DRIVER) {
                var company = fixtures.approvedCompany();
                var driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
                jdbcTemplate.update("UPDATE drivers SET user_id=?, phone=? WHERE id=?", user.getId(), user.getPhone(), driver.getId());
            }
            String auth = bearer(tokenFor(user));
            for (var request : List.of(get(f.base()), get(f.base()+"/"+f.document().getId()+"/file"),
                    post(f.base()+"/"+f.document().getId()+"/approve"),
                    post(f.base()+"/"+f.document().getId()+"/reject").contentType(APPLICATION_JSON).content("{\"reason\":\"No\"}"))) {
                int status = mockMvc.perform(request.header("Authorization", auth)).andReturn().getResponse().getStatus();
                assertThat(status).isIn(401, 403);
            }
        }
        assertThat(documents.findById(f.document().getId()).orElseThrow().getVerificationStatus()).isEqualTo(DocumentVerificationStatus.DRAFT);
    }
    @Test void companyOwnerAndDocumentMustMatch() throws Exception {
        var first = fixture(DocumentOwnerType.DRIVER); var second = fixture(DocumentOwnerType.DRIVER); String auth = admin();
        String wrongCompany = first.base().replace("partners/" + first.company() + "/", "partners/" + second.company() + "/");
        mockMvc.perform(get(wrongCompany).header("Authorization", auth)).andExpect(status().isNotFound());
        mockMvc.perform(post(first.base()+"/"+second.document().getId()+"/approve").header("Authorization", auth)).andExpect(status().isNotFound());
        mockMvc.perform(get(first.base()+"/"+second.document().getId()+"/file").header("Authorization", auth)).andExpect(status().isNotFound());
    }
    @Test void companyDocumentsCannotEnterFleetReview() throws Exception {
        var f = fixture(DocumentOwnerType.DRIVER);
        mockMvc.perform(get(f.base().replace("/DRIVER/", "/COMPANY/")).header("Authorization", admin())).andExpect(status().isBadRequest());
    }
    @Test void expiredFutureDatedOrMissingFileCannotBeApproved() throws Exception {
        String auth = admin();
        for (int scenario=0; scenario<3; scenario++) {
            var f = fixture(DocumentOwnerType.VEHICLE);
            if (scenario==0) jdbcTemplate.update("UPDATE documents SET expires_at=? WHERE id=?", LicenseValidity.today().minusDays(1), f.document().getId());
            if (scenario==1) jdbcTemplate.update("UPDATE documents SET issued_at=? WHERE id=?", LicenseValidity.today().plusDays(1), f.document().getId());
            if (scenario==2) storage.discard(f.document().getFileUrl());
            int code = mockMvc.perform(post(f.base()+"/"+f.document().getId()+"/approve").header("Authorization", auth)).andReturn().getResponse().getStatus();
            assertThat(code).isIn(400,404);
            assertThat(documents.findById(f.document().getId()).orElseThrow().getVerificationStatus()).isEqualTo(DocumentVerificationStatus.DRAFT);
        }
    }
    @Test void repeatedDecisionCannotOverwriteApproval() throws Exception {
        var f = fixture(DocumentOwnerType.DRIVER); String auth=admin();
        String url=f.base()+"/"+f.document().getId();
        mockMvc.perform(post(url+"/approve").header("Authorization",auth)).andExpect(status().isOk());
        mockMvc.perform(post(url+"/reject").header("Authorization",auth).contentType(APPLICATION_JSON).content("{\"reason\":\"Changed mind\"}"))
                .andExpect(status().isBadRequest());
        assertThat(documents.findById(f.document().getId()).orElseThrow().getRejectionReason()).isNull();
    }
    @Test void simultaneousDecisionsPreserveExactlyOneCommittedResult() throws Exception {
        var f=fixture(DocumentOwnerType.VEHICLE); String auth=admin(); String url=f.base()+"/"+f.document().getId();
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var approve=pool.submit(() -> { start.await(); return mockMvc.perform(post(url+"/approve").header("Authorization",auth)).andReturn().getResponse().getStatus(); });
            var reject=pool.submit(() -> { start.await(); return mockMvc.perform(post(url+"/reject").header("Authorization",auth).contentType(APPLICATION_JSON).content("{\"reason\":\"Unreadable\"}"))
                    .andReturn().getResponse().getStatus(); });
            start.countDown();
            int a=approve.get(15,TimeUnit.SECONDS), b=reject.get(15,TimeUnit.SECONDS);
            assertThat(List.of(a,b)).containsExactlyInAnyOrder(200,400);
            var saved=documents.findById(f.document().getId()).orElseThrow();
            assertThat(saved.getVerificationStatus()).isEqualTo(a==200 ? DocumentVerificationStatus.APPROVED : DocumentVerificationStatus.REJECTED);
        } finally { pool.shutdownNow(); pool.awaitTermination(15,TimeUnit.SECONDS); }
    }
}
