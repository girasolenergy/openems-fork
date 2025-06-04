package io.openems.backend.b2bwebsocket;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Backend2Backend.Websocket", //
		description = "Provides a websocket server for backend-to-backend communication.")
@interface Config {
@AttributeDefinition(name = "IP-Address", description = "The IP address of the websocket server. ('0.0.0.0' for any IP)")
	String ip() default "127.0.0.1";
	
	@AttributeDefinition(name = "Port", description = "The port of the websocket server.")
	int port() default Backend2BackendWebsocket.DEFAULT_PORT;

	@AttributeDefinition(name = "Number of Threads", description = "Pool-Size: the number of threads dedicated to handle the tasks")
	int poolSize() default 10;

	String webconsole_configurationFactory_nameHint() default "Backend2Backend Websocket";
}