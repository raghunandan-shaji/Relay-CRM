package com.raghu.crm.customer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomerNamesTests {

	@Test
	void normalizesWhitespaceCaseAndCompatibilityCharacters() {
		assertEquals("raghu", CustomerNames.normalize("  ＲＡＧＨＵ  "));
		assertEquals("rahul suresh", CustomerNames.normalize(" Rahul Suresh "));
	}

	@Test
	void unicodeRangeIncludesExactlyThePrefixAndUsesMongoBinaryOrder() {
		assertEquals("rb", CustomerNames.upperBound("ra"));
		String maximum = new String(Character.toChars(Character.MAX_CODE_POINT));
		assertEquals("rb", CustomerNames.upperBound("ra" + maximum));
		assertNull(CustomerNames.upperBound(maximum));
		assertTrue(CustomerNames.compare("ra", "rahul") < 0);
		assertTrue(CustomerNames.compare("\uE000", "😀") < 0);
	}

	@Test
	void searchLimitsAreBoundedAndRegexCharactersAreLiteral() {
		assertThrows(IllegalArgumentException.class, () -> CustomerSearchService.prefixQuery(" ", 10));
		assertThrows(IllegalArgumentException.class, () -> CustomerSearchService.prefixQuery("ra", 51));
		String query = CustomerSearchService.prefixQuery(".*", 10).getQueryObject().toJson();
		assertTrue(query.contains("$gte"));
		assertTrue(!query.contains("$regex"));
	}
}
