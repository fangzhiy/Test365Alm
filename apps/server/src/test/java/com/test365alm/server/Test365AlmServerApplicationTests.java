package com.test365alm.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.springframework.boot.autoconfigure.SpringBootApplication;

class Test365AlmServerApplicationTests {

	@Test
	void applicationIsMarkedAsSpringBootApplication() {
		assertTrue(Test365AlmServerApplication.class.isAnnotationPresent(SpringBootApplication.class));
	}

}
