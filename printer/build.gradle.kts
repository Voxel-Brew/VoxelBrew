plugins { `java-library` }

base { archivesName.set("printer-impl") }

dependencies {
    api("dev.voxelbrew.api:printer")
    implementation(project(":util"))
    implementation("com.google.code.gson:gson")
    implementation("pl.grzeslowski:JBambuAPI")
    implementation("commons-net:commons-net")
}
