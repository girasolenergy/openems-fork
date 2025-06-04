package io.openems.backend.b2brest;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Backend2Backend.Rest", //
		description = "Provides a REST-Api server for backend-to-backend communication.")
@interface Config {
	@AttributeDefinition(name = "IP-Address", description = "The IP address of the REST server. ('0.0.0.0' for any IP)")
	String ip() default "127.0.0.1";

	@AttributeDefinition(name = "Port", description = "The port of the REST server.")
	int port() default Backend2BackendRest.DEFAULT_PORT;

	String webconsole_configurationFactory_nameHint() default "Backend2Backend Rest";

}