package com.raghu.crm.sales;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesRulesTests {

	@Test
	void leadQualificationHasOneDedicatedPathAndClosedStatesAreTerminal() {
		assertTrue(LeadStatus.NEW.canChangeTo(LeadStatus.CONTACTED));
		assertTrue(LeadStatus.NEW.canChangeTo(LeadStatus.LOST));
		assertTrue(LeadStatus.CONTACTED.canQualify());
		assertTrue(LeadStatus.NEW.canQualify());
		assertFalse(LeadStatus.NEW.canChangeTo(LeadStatus.QUALIFIED));
		assertFalse(LeadStatus.LOST.canQualify());
		assertFalse(LeadStatus.QUALIFIED.canQualify());
		for (LeadStatus stage : LeadStatus.values()) {
			assertFalse(LeadStatus.LOST.canChangeTo(stage));
			assertFalse(LeadStatus.QUALIFIED.canChangeTo(stage));
		}
	}

	@Test
	void opportunitiesOnlyMoveForwardAndCannotReopen() {
		assertTrue(OpportunityStage.OPEN.canChangeTo(OpportunityStage.PROPOSAL));
		assertTrue(OpportunityStage.OPEN.canChangeTo(OpportunityStage.LOST));
		assertTrue(OpportunityStage.PROPOSAL.canChangeTo(OpportunityStage.WON));
		assertTrue(OpportunityStage.NEGOTIATION.canChangeTo(OpportunityStage.WON));
		assertFalse(OpportunityStage.NEGOTIATION.canChangeTo(OpportunityStage.PROPOSAL));
		assertFalse(OpportunityStage.OPEN.canChangeTo(OpportunityStage.OPEN));
		for (OpportunityStage stage : OpportunityStage.values()) {
			assertFalse(OpportunityStage.WON.canChangeTo(stage));
			assertFalse(OpportunityStage.LOST.canChangeTo(stage));
		}
	}
}
