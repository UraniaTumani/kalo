package com.kalo.admin.controller;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.document.enums.DocumentOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Small, company-scoped picker for the admin document-review screen. */
@RestController
@RequiredArgsConstructor
public class FleetDocumentOwnerController {
    private final JdbcTemplate jdbc;
    public record Owner(Long id, String label) {}
    public record Owners(List<Owner> items, boolean hasMore) {}

    @GetMapping("/api/v1/admin/partners/{companyId}/fleet/owners")
    @Transactional(readOnly = true)
    public ResponseEntity<Owners> owners(@PathVariable Long companyId,
            @RequestParam DocumentOwnerType ownerType, @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 100000) throw new InvalidOperationException("Invalid page");
        String table, label;
        if (ownerType == DocumentOwnerType.DRIVER) {
            table = "drivers"; label = "first_name || ' ' || last_name || ' (' || license_number || ')'";
        } else if (ownerType == DocumentOwnerType.VEHICLE) {
            table = "vehicles"; label = "plate_number || ' - ' || brand || ' ' || model";
        } else throw new InvalidOperationException("Only DRIVER or VEHICLE are supported");
        Long exists = jdbc.queryForObject("SELECT count(*) FROM taxi_companies WHERE id=?", Long.class, companyId);
        if (exists == null || exists != 1L) throw new ResourceNotFoundException("Taxi company not found");
        // Table/expression are constants chosen above, never interpolated user text.
        var rows = jdbc.query("SELECT id, " + label + " AS label FROM " + table
                + " WHERE company_id=? ORDER BY id LIMIT 21 OFFSET ?",
                (rs, n) -> new Owner(rs.getLong("id"), rs.getString("label")), companyId, page * 20);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Owners(List.copyOf(rows.subList(0, Math.min(rows.size(), 20))), rows.size() > 20));
    }
}
