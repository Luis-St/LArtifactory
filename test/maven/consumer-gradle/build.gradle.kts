plugins {
	application
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

repositories {
	maven {
		url = uri(providers.gradleProperty("artifactoryUrl").get() + "/maven/maven-releases")
		isAllowInsecureProtocol = true
		credentials {
			username = providers.gradleProperty("artifactoryUser").get()
			password = providers.gradleProperty("artifactoryPassword").get()
		}
	}
}

dependencies {
	implementation("net.luis.lartifactory.test:gradle-lib:1.0.0")
	implementation("net.luis.lartifactory.test:maven-lib:1.0.0")
}

application {
	mainClass.set("net.luis.lartifactory.test.consumer.Consumer")
}
