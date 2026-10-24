package com.raghu.crm.sales;

import com.raghu.crm.auth.StaffAccounts;
import com.raghu.crm.customer.CustomerResponse;
import com.raghu.crm.customer.CustomerService;
import com.raghu.crm.systemdesign.sharding.ConsistentHashRing;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class SalesRepresentativeService {

	private final StaffAccounts staff;
	private final CustomerService customers;
	private volatile AssignmentRing cachedRing;

	public SalesRepresentativeService(StaffAccounts staff, CustomerService customers) {
		this.staff = staff;
		this.customers = customers;
	}

	public List<Representative> available() {
		List<Representative> representatives = new ArrayList<>();
		for (String email : staff.emails()) {
			CustomerResponse customer = customers.findByEmail(email);
			if (customer != null && staff.contains(customer.email())) {
				representatives.add(new Representative(customer.id(), customer.fullName(), customer.email()));
			}
		}
		representatives.sort(Comparator.comparing(Representative::id));
		return List.copyOf(representatives);
	}

	public String assign(String leadId, String requestedId) {
		List<Representative> available = available();
		if (requestedId != null) {
			if (available.stream().noneMatch(rep -> rep.id().equals(requestedId))) {
				throw new SalesException(HttpStatus.BAD_REQUEST, "Representative must be a registered, configured staff account");
			}
			return requestedId;
		}
		if (available.isEmpty()) {
			throw new SalesException(HttpStatus.SERVICE_UNAVAILABLE, "No registered staff representatives are available");
		}
		List<String> ids = available.stream().map(Representative::id).toList();
		AssignmentRing current = cachedRing;
		if (current == null || !current.ids().equals(ids)) {
			current = new AssignmentRing(ids, new ConsistentHashRing(ids, 256));
			cachedRing = current;
		}
		return current.ring().nodeFor(leadId);
	}

	public record Representative(String id, String fullName, String email) {
	}

	private record AssignmentRing(List<String> ids, ConsistentHashRing ring) {
	}
}
