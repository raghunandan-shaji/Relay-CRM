package com.raghu.crm.support;

public enum TicketStatus {
	OPEN, IN_PROGRESS, RESOLVED, CLOSED;
	boolean canChangeTo(TicketStatus next) { return next.ordinal() > ordinal(); }
}
