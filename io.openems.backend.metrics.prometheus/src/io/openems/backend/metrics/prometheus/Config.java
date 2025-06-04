package io.openems.backend.metrics.prometheus;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Metrics.Prometheus", //
		description = "Metrics endpoint for prometheus.")
@interface Config {

	@AttributeDefinition(name = "IP-Address", description = "The IP address to bind the metrics server. (0.0.0.0 for any IP)")
	String ip() default "127.0.0.1";

	@AttributeDefinition(name = "Port", description = "Http port")
	int port() default 9400;

	@AttributeDefinition(name = "Bearer Token", description = "Endpoint secured with a bearer token. Leave empty to disable authentication.", required = false)
	String bearerToken();

	String webconsole_configurationFactory_nameHint() default "Prometheus Client";

}