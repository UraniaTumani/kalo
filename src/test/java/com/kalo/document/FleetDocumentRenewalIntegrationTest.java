package com.kalo.document;

import com.jayway.jsonpath.JsonPath;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.common.util.LicenseValidity;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.*;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.document.service.LocalDocumentStorage;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FleetDocumentRenewalIntegrationTest extends AbstractIntegrationTest {
    @Autowired DocumentRepository documents;
    @Autowired LocalDocumentStorage storage;
    @Autowired TransactionTemplate transactions;
    @Autowired com.kalo.notification.service.DocumentExpiryNotificationSweepService expirySweep;
    private record Fixture(String base, String adminBase, String auth, Long id) {}
    private Fixture fixture(DocumentOwnerType type, DocumentVerificationStatus status, LocalDate expiry) throws Exception {
        var c=fixtures.approvedCompany();
        Long owner=type==DocumentOwnerType.DRIVER
                ? fixtures.driver(c, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE).getId()
                : fixtures.vehicle(c,VehicleStatus.ACTIVE).getId();
        var d=new Document(); d.setOwnerType(type); d.setOwnerId(owner);
        d.setDocumentType(type==DocumentOwnerType.DRIVER ? DocumentType.DRIVING_LICENSE : DocumentType.INSURANCE);
        d.setFileUrl(storage.store(file("original"))); d.setExpiresAt(expiry); d.setVerificationStatus(status);
        d.setRejectionReason(status==DocumentVerificationStatus.REJECTED ? "Old reason" : null);
        documents.saveAndFlush(d);
        return new Fixture("/api/v1/partner/"+(type==DocumentOwnerType.DRIVER ? "drivers/" : "vehicles/")+owner+"/documents",
                "/api/v1/admin/partners/"+c.getId()+"/fleet/"+type+"/"+owner+"/documents",
                bearer(tokenFor(c.getOwner())),d.getId());
    }
    private MockMultipartFile file(String text) { return new MockMultipartFile("file","proof.pdf","application/pdf",("%PDF-1.4\n"+text).getBytes(StandardCharsets.UTF_8)); }
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder renewal(Fixture f, String auth, String date) {
        var builder = multipart(f.base()+"/"+f.id()+"/renew");
        builder.file(file("replacement"));
        builder.param("documentNumber","NEW-1").param("issuedAt",LicenseValidity.today().toString())
                .param("expiresAt",date);
        builder.header("Authorization",auth);
        return builder;
    }
    private String renew(Fixture f) throws Exception {
        return mockMvc.perform(renewal(f,f.auth(),LicenseValidity.today().plusYears(1).toString()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
    }
    @AfterEach void cleanupFiles() { documents.findAll().forEach(d -> { if(d.getFileUrl().startsWith("local:")) storage.discard(d.getFileUrl()); }); }
    @Test void renewalPreservesOldEvidenceAndShowsOnlyLatestToPartnerAndAdmin() throws Exception {
        String admin=bearer(tokenFor(fixtures.user(UserRole.ADMIN,UserStatus.ACTIVE)));
        for(var type:new DocumentOwnerType[]{DocumentOwnerType.DRIVER,DocumentOwnerType.VEHICLE}) {
            var f=fixture(type,DocumentVerificationStatus.REJECTED,LicenseValidity.today().plusYears(1));
            String response=renew(f); Long replacement=((Number)JsonPath.read(response,"$.id")).longValue();
            assertThat(replacement).isNotEqualTo(f.id());
            var old=documents.findById(f.id()).orElseThrow();
            assertThat(old.getRejectionReason()).isEqualTo("Old reason");
            assertThat(old.getVerificationStatus()).isEqualTo(DocumentVerificationStatus.REJECTED);
            mockMvc.perform(get(f.base()).header("Authorization",f.auth())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(replacement));
            mockMvc.perform(get(f.base()+"/history").header("Authorization",f.auth())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].id").value(f.id()));
            mockMvc.perform(get(f.base()+"/"+f.id()+"/file").header("Authorization",f.auth())).andExpect(status().isOk());
            mockMvc.perform(get(f.adminBase()).header("Authorization",admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(replacement));
            mockMvc.perform(post(f.adminBase()+"/"+replacement+"/approve").header("Authorization",admin)).andExpect(status().isOk());
        }
    }
    @Test void nonexpiredApprovedOrPendingCannotBeReplaced() throws Exception {
        for(var status:new DocumentVerificationStatus[]{DocumentVerificationStatus.APPROVED,DocumentVerificationStatus.PENDING,DocumentVerificationStatus.DRAFT}) {
            var f=fixture(DocumentOwnerType.VEHICLE,status,LicenseValidity.today().plusYears(1));
            mockMvc.perform(renewal(f,f.auth(),LicenseValidity.today().plusYears(1).toString())).andExpect(status().isBadRequest());
        }
    }
    @Test void expiredApprovedCanBeRenewedWithoutChangingOldApproval() throws Exception {
        var f=fixture(DocumentOwnerType.DRIVER,DocumentVerificationStatus.APPROVED,LicenseValidity.today().minusDays(1));
        renew(f);
        assertThat(documents.findById(f.id()).orElseThrow().getVerificationStatus()).isEqualTo(DocumentVerificationStatus.APPROVED);
    }
    @Test void invalidDatesAndMissingFileDoNotCreateVersion() throws Exception {
        var f=fixture(DocumentOwnerType.VEHICLE,DocumentVerificationStatus.REJECTED,null);
        mockMvc.perform(renewal(f,f.auth(),LicenseValidity.today().minusDays(1).toString())).andExpect(status().isBadRequest());
        mockMvc.perform(multipart(f.base()+"/"+f.id()+"/renew").file(file("new")).header("Authorization",f.auth())).andExpect(status().isBadRequest());
        mockMvc.perform(multipart(f.base()+"/"+f.id()+"/renew").param("expiresAt",LicenseValidity.today().plusYears(1).toString())
                .header("Authorization",f.auth())).andExpect(status().isBadRequest());
        assertThat(documents.count()).isEqualTo(1);
    }
    @Test void foreignPartnerAndOtherRolesCannotRenewOrReadHistory() throws Exception {
        var f=fixture(DocumentOwnerType.DRIVER,DocumentVerificationStatus.REJECTED,null);
        String foreign=bearer(tokenFor(fixtures.approvedCompany().getOwner()));
        mockMvc.perform(renewal(f,foreign,LicenseValidity.today().plusYears(1).toString())).andExpect(status().isNotFound());
        mockMvc.perform(get(f.base()+"/history").header("Authorization",foreign)).andExpect(status().isNotFound());
        for(var role:new UserRole[]{UserRole.CUSTOMER,UserRole.ADMIN}) {
            String auth=bearer(tokenFor(fixtures.user(role,UserStatus.ACTIVE)));
            mockMvc.perform(renewal(f,auth,LicenseValidity.today().plusYears(1).toString())).andExpect(status().isForbidden());
        }
        mockMvc.perform(multipart(f.base()+"/"+f.id()+"/renew").file(file("new"))).andExpect(status().isUnauthorized());
    }
    @Test void supersededIdsCannotBeReviewedOrRenewedAgain() throws Exception {
        var f=fixture(DocumentOwnerType.DRIVER,DocumentVerificationStatus.DRAFT,LicenseValidity.today().minusDays(1));
        renew(f); String admin=bearer(tokenFor(fixtures.user(UserRole.ADMIN,UserStatus.ACTIVE)));
        mockMvc.perform(post(f.adminBase()+"/"+f.id()+"/reject").header("Authorization",admin).contentType("application/json")
                .content("{\"reason\":\"Stale review\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(renewal(f,f.auth(),LicenseValidity.today().plusYears(1).toString())).andExpect(status().isConflict());
        assertThat(documents.count()).isEqualTo(2);
    }
    @Test void concurrentRenewalsProduceOnePendingVersion() throws Exception {
        var f=fixture(DocumentOwnerType.VEHICLE,DocumentVerificationStatus.REJECTED,null);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> task=() -> {start.await();return mockMvc.perform(renewal(f,f.auth(),LicenseValidity.today().plusYears(1).toString())).andReturn().getResponse().getStatus();};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            assertThat(documents.count()).isEqualTo(2);
        } finally {pool.shutdownNow();pool.awaitTermination(15,TimeUnit.SECONDS);}
    }
    @Test void rollbackRemovesNewFileAndRowButKeepsOldEvidence() throws Exception {
        var f=fixture(DocumentOwnerType.VEHICLE,DocumentVerificationStatus.REJECTED,null);
        String reference=transactions.execute(tx -> {
            try {String response=renew(f);String ref=JsonPath.read(response,"$.fileUrl");tx.setRollbackOnly();return ref;}
            catch(Exception ex){throw new IllegalStateException(ex);}
        });
        assertThat(documents.count()).isEqualTo(1);
        assertThatThrownBy(() -> storage.read(reference)).isInstanceOf(com.kalo.common.exception.ResourceNotFoundException.class);
        assertThat(storage.read(documents.findById(f.id()).orElseThrow().getFileUrl()).bytes()).isNotEmpty();
    }
    @Test void expirySweepDoesNotNotifyForSupersededEvidence() throws Exception {
        var f=fixture(DocumentOwnerType.VEHICLE,DocumentVerificationStatus.APPROVED,LicenseValidity.today().minusDays(1));
        renew(f);expirySweep.createDueNotifications();
        Long count=jdbcTemplate.queryForObject("SELECT count(*) FROM company_notifications WHERE notification_key LIKE ?",Long.class,"document:"+f.id()+":%");
        assertThat(count).isZero();
    }
}
