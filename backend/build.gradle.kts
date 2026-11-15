plugins { application }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
application { mainClass.set("pawriro.backend.Api") }
dependencies {
    implementation("org.postgresql:postgresql:42.7.10")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")
}
