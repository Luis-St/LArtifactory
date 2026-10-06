plugins {
	`java-library`
	`maven-publish`
}

group = "net.luis.lartifactory.test"
version = providers.gradleProperty("libVersion").getOrElse("1.0.0")

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	withSourcesJar()
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])
		}
	}
	repositories {
		maven {
			name = "lartifactory"
			val base = providers.gradleProperty("artifactoryUrl").get()
			url = uri(if (version.toString().endsWith("-SNAPSHOT")) "$base/maven/maven-snapshots" else "$base/maven/maven-releases")
			isAllowInsecureProtocol = true
			credentials {
				username = providers.gradleProperty("artifactoryUser").get()
				password = providers.gradleProperty("artifactoryPassword").get()
			}
		}
	}
}
