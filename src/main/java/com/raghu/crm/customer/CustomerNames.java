package com.raghu.crm.customer;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

final class CustomerNames {

	private CustomerNames() {
	}

	static String normalize(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
	}

	static String upperBound(String prefix) {
		for (int end = prefix.length(); end > 0;) {
			int point = prefix.codePointBefore(end);
			int start = end - Character.charCount(point);
			if (point < Character.MAX_CODE_POINT) {
				int next = point + 1;
				if (next >= Character.MIN_SURROGATE && next <= Character.MAX_SURROGATE) next = Character.MAX_SURROGATE + 1;
				return prefix.substring(0, start) + new String(Character.toChars(next));
			}
			end = start;
		}
		return null;
	}

	static int compare(String first, String second) {
		return Arrays.compareUnsigned(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
	}
}
