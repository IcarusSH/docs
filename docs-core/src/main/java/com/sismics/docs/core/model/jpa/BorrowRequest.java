package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;
import com.sismics.docs.core.constant.BorrowStatus;
import com.sismics.docs.core.constant.PermType;

import jakarta.persistence.*;
import java.util.Date;

/**
 * Borrow request entity. Tracks a user's request to access a document they only have
 * SEARCHABLE permission on. When approved, a temporary T_ACL row of AclType.BORROW is
 * created and {@link #aclId} points to it. The borrow is revoked automatically by
 * BorrowExpiryService once {@link #expiryDate} passes.
 *
 * Lifecycle states are tracked by {@link #status}.
 *
 * @author Claude
 */
@Entity
@Table(name = "T_BORROW_REQUEST")
public class BorrowRequest implements Loggable {

    @Id
    @Column(name = "BOR_ID_C", length = 36)
    private String id;

    @Column(name = "BOR_IDDOC_C", length = 36, nullable = false)
    private String documentId;

    @Column(name = "BOR_IDREQUSER_C", length = 36, nullable = false)
    private String requesterId;

    @Column(name = "BOR_IDAPPUSER_C", length = 36, nullable = false)
    private String approverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "BOR_STATUS_C", length = 20, nullable = false)
    private BorrowStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "BOR_PERM_C", length = 30, nullable = false)
    private PermType perm;

    @Column(name = "BOR_DURATIONDAYS_N", nullable = false)
    private int durationDays;

    @Column(name = "BOR_NOTE_C", length = 2000)
    private String note;

    @Column(name = "BOR_CREATEDATE_D")
    private Date createDate;

    @Column(name = "BOR_DECISIONDATE_D")
    private Date decisionDate;

    @Column(name = "BOR_EXPIRYDATE_D")
    private Date expiryDate;

    @Column(name = "BOR_ACL_ID_C", length = 36)
    private String aclId;

    @Column(name = "BOR_DELETEDATE_D")
    private Date deleteDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }

    public String getApproverId() { return approverId; }
    public void setApproverId(String approverId) { this.approverId = approverId; }

    public BorrowStatus getStatus() { return status; }
    public void setStatus(BorrowStatus status) { this.status = status; }

    public PermType getPerm() { return perm; }
    public void setPerm(PermType perm) { this.perm = perm; }

    public int getDurationDays() { return durationDays; }
    public void setDurationDays(int durationDays) { this.durationDays = durationDays; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Date getCreateDate() { return createDate; }
    public void setCreateDate(Date createDate) { this.createDate = createDate; }

    public Date getDecisionDate() { return decisionDate; }
    public void setDecisionDate(Date decisionDate) { this.decisionDate = decisionDate; }

    public Date getExpiryDate() { return expiryDate; }
    public void setExpiryDate(Date expiryDate) { this.expiryDate = expiryDate; }

    public String getAclId() { return aclId; }
    public void setAclId(String aclId) { this.aclId = aclId; }

    @Override
    public Date getDeleteDate() { return deleteDate; }
    public void setDeleteDate(Date deleteDate) { this.deleteDate = deleteDate; }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("documentId", documentId)
                .add("requesterId", requesterId)
                .add("approverId", approverId)
                .add("status", status)
                .add("perm", perm)
                .add("durationDays", durationDays)
                .toString();
    }

    @Override
    public String toMessage() {
        // Avoid exposing note (could contain PII). Perm + duration is enough for audit.
        return perm.name() + " " + durationDays + "d";
    }
}