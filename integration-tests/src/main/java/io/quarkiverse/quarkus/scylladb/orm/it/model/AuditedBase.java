package io.quarkiverse.quarkus.scylladb.orm.it.model;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;

/**
 * Superclass so that inherited fields are actually exercised — the generators walk the
 * superclass chain, and nothing tested that they do.
 */
public abstract class AuditedBase {

    @Column("created_by")
    private String createdBy;

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
