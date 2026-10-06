plugins {
	java
	id("com.gradleup.shadow") version "9.4.1"
}

group = "net.luis"
version = "1.0.0"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(25))
	}
}

repositories {
	mavenCentral()
	maven {
		url = uri("https://maven.luis-st.net/libraries/")
	}
}

dependencies {
	// LUtils
	implementation(libs.lutils)
	
	// Utility
	implementation(libs.apache.commons.lang3)
	implementation(libs.google.guava) {
		exclude(group = "org.checkerframework")
		exclude(group = "com.google.code.findbugs")
		exclude(group = "com.google.errorprone")
		exclude(group = "com.google.j2objc")
	}
	
	// Javalin
	implementation(libs.javalin)
	implementation(libs.javalin.bundle) {
		exclude(group = "ch.qos.logback", module = "logback-classic")
	}
	
	// OpenAPI
	implementation(libs.javalin.openapi.plugin)
	implementation(libs.javalin.swagger.plugin)
	annotationProcessor(libs.javalin.openapi.annotation.processor)
	
	// Jackson JSON
	implementation(libs.jackson.databind)
	
	// Logging
	implementation(libs.log4j2.api)
	implementation(libs.log4j2.core)
	implementation(libs.log4j2.slf4j2.impl)
	
	// Database
	implementation(libs.hikaricp)
	runtimeOnly(libs.postgresql)
	
	// Nullability
	implementation(libs.jspecify)
	
	// Testing
	testImplementation(platform("org.junit:junit-bom:6.0.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
}

tasks.register<JavaExec>("run") {
	description = "Runs the application"
	group = "api"
	
	mainClass.set("net.luis.artifactory.Application")
	classpath = sourceSets["main"].runtimeClasspath
	
	enableAssertions = true
	standardInput = System.`in`
	args = listOf()
	environment("ARTIFACTORY_PORT", "8080")
	environment("ARTIFACTORY_DB_URL", "jdbc:postgresql://localhost:5432/artifactory")
	environment("ARTIFACTORY_DB_USERNAME", "artifactory")
	environment("ARTIFACTORY_DB_PASSWORD", "artifactory")
	environment("ARTIFACTORY_ADMIN_PASSWORD", "admin")
	environment("ARTIFACTORY_STORAGE_PATH", layout.buildDirectory.dir("data").get().asFile.absolutePath)
}

val generateOpenApi = tasks.register("generateOpenApi") {
	description = "Generates the OpenAPI specification file"
	group = "api"
	
	dependsOn(tasks.compileJava)
	
	val source = layout.buildDirectory.file("classes/java/main/openapi-plugin/openapi-default.json")
	val target = layout.projectDirectory.file("openapi.json")
	
	inputs.file(source)
	outputs.file(target)
	
	doLast {
		source.get().asFile.copyTo(target.asFile, overwrite = true)
	}
}

tasks.compileJava {
	finalizedBy(generateOpenApi)
}

tasks.shadowJar {
	group = "api"
	
	archiveClassifier.set("")
	mergeServiceFiles()
	manifest {
		attributes("Main-Class" to "net.luis.artifactory.Application")
	}
}
