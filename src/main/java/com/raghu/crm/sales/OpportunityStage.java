package com.raghu.crm.sales;

public enum OpportunityStage {
	OPEN, PROPOSAL, NEGOTIATION, WON, LOST;

	public boolean closed() {
		return this == WON || this == LOST;
	}

	boolean canChangeTo(OpportunityStage next) {
		return !closed() && next != null && next.ordinal() > ordinal();
	}
}
