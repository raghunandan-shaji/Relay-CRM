package com.raghu.crm.sales;

public enum LeadStatus {
	NEW, CONTACTED, QUALIFIED, LOST;

	boolean canChangeTo(LeadStatus next) {
		return (this == NEW && (next == CONTACTED || next == LOST)) || (this == CONTACTED && next == LOST);
	}

	boolean canQualify() {
		return this == NEW || this == CONTACTED;
	}
}
